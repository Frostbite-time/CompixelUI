package dev.compixel.sync.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.MenuSyncOptions;
import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionResult;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncSchema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One client session and the server's sessions, connected through in-memory message queues. */
class SessionLoopbackTest {
    static final class Menu {
        String text = "";
        int counter;
    }

    static final class Player {
        final UUID id = UUID.randomUUID();
        Object openMenu;
        boolean present = true;
    }

    static final SyncSchema<Menu> SCHEMA = SyncSchema.<Menu>builder("test:loopback", 1)
            .field("text", SyncCodecs.string(64 * 1024), m -> m.text, (m, v) -> m.text = v)
            .field("counter", SyncCodecs.INT, m -> m.counter, (m, v) -> m.counter = v)
            .build();
    static final TestBindings.Action<Menu, Player, Integer> ADD =
            new TestBindings.Action<>("add", SyncCodecs.INT, (menu, player, value) -> {
                if (value <= 0) return false;
                menu.counter += value;
                return true;
            });
    static final TestBindings.Action<Menu, Player, String> TEXT =
            new TestBindings.Action<>("text", SyncCodecs.string(64 * 1024), 64 * 1024, (menu, player, value) -> {
                menu.text = value;
                return true;
            });
    static final TestBindings.Action<Menu, Player, Integer> UNDECLARED =
            new TestBindings.Action<>("undeclared", SyncCodecs.INT, (menu, player, value) -> true);

    private final Object server = new Object(), connection = new Object();
    private final Player player = new Player();
    private final ArrayDeque<SyncMessage> toClient = new ArrayDeque<>(), toServer = new ArrayDeque<>();
    private final List<String> warnings = new ArrayList<>();
    private long serverTick;
    private SyncBinding<?, ?, ?> clientBinding;
    private Object clientMenu;

    private final ServerSyncSessions<Player> sessions = new ServerSyncSessions<>(new ServerSyncSessions.Host<>() {
        public UUID id(Player p) {
            return p.id;
        }

        public Object server(Player p) {
            return server;
        }

        public long tick(Player p) {
            return serverTick;
        }

        public boolean current(Player p) {
            return p.present;
        }

        public Object connection(Player p) {
            return connection;
        }

        public boolean channelsAvailable(Player p) {
            return true;
        }

        public boolean writable(Player p) {
            return true;
        }

        public Object openMenu(Player p) {
            return p.openMenu;
        }

        public boolean mayAct(Player p) {
            return true;
        }

        public CodecScope codecs(Player p) {
            return CodecScope.NONE;
        }

        public void send(Player p, SyncMessage message) {
            toClient.add(message);
        }

        public SyncLog log() {
            return (message, error) -> warnings.add(message);
        }
    });

    private final ClientSyncSession client = new ClientSyncSession(new ClientSyncSession.Host() {
        public Object connection() {
            return connection;
        }

        public Object openMenu() {
            return clientMenu;
        }

        public SyncBinding<?, ?, ?> openBinding() {
            return clientBinding != null && clientBinding.menu() == clientMenu ? clientBinding : null;
        }

        public boolean channelsAvailable() {
            return true;
        }

        public boolean controlAvailable() {
            return true;
        }

        public boolean writable() {
            return true;
        }

        public boolean onGameThread() {
            return true;
        }

        public CodecScope codecs() {
            return CodecScope.NONE;
        }

        public void send(SyncMessage message) {
            toServer.add(message);
        }

        public SyncLog log() {
            return (message, error) -> warnings.add(message);
        }
    });

    private static TestBindings.Binding<Menu, Player> binding(Menu menu) {
        return new TestBindings.Binding<Menu, Player>(menu, SCHEMA, MenuSyncOptions.DEFAULT, 7)
                .action(ADD)
                .action(TEXT);
    }

    /** Delivers queued messages both ways until none are left. */
    private void deliver() {
        for (int i = 0; i < 1000 && (!toClient.isEmpty() || !toServer.isEmpty()); i++) {
            var up = toServer.poll();
            if (up instanceof SyncMessage.Control control) sessions.receive(player, control);
            else if (up instanceof SyncMessage.ActionRequest request) sessions.receive(player, request);
            else if (up instanceof SyncMessage.ActionFragment fragment) sessions.receive(player, fragment);
            else if (up != null) fail("Unexpected client message " + up);
            var down = toClient.poll();
            if (down instanceof SyncMessage.Bootstrap bootstrap) client.receive(bootstrap);
            else if (down instanceof SyncMessage.Data data) client.receive(data);
            else if (down instanceof SyncMessage.ActionReply reply) client.receive(reply);
            else if (down != null) fail("Unexpected server message " + down);
        }
        assertTrue(toClient.isEmpty() && toServer.isEmpty(), "Messages kept bouncing");
    }

    private void step() {
        serverTick++;
        sessions.tick(server);
        client.tick();
        deliver();
    }

    /** Opens the menu on both sides and returns the server's and the client's bindings. */
    private List<TestBindings.Binding<Menu, Player>> open(Menu serverMenu, Menu clientMenu) {
        var serverBinding = binding(serverMenu);
        var clientSide = binding(clientMenu);
        this.clientMenu = clientMenu;
        clientBinding = clientSide;
        player.openMenu = serverMenu;
        sessions.opened(player, serverMenu, serverBinding);
        deliver();
        return List.of(serverBinding, clientSide);
    }

    @Test
    void stateArrivesAndActionsComeBackAsResults() {
        var serverMenu = new Menu();
        serverMenu.text = "server state";
        var clientMenu = new Menu();
        var bindings = open(serverMenu, clientMenu);
        var serverSide = bindings.get(0);
        var clientSide = bindings.get(1);
        var results = new ArrayList<ActionResult>();
        assertTrue(clientSide.hasSnapshot());
        assertEquals("server state", clientMenu.text);
        assertEquals(SyncStatus.READY, serverSide.status());

        assertTrue(client.request(clientSide, ADD, 5).queued());
        deliver();
        assertEquals(5, serverMenu.counter);
        results.add(clientSide.lastActionResult());
        assertEquals(ActionStatus.APPLIED, results.get(0).status());
        assertEquals(1, results.get(0).sequence());
        step();
        assertEquals(5, clientMenu.counter);

        // 48,000 bytes, three fragments: the server assembles them before the handler runs.
        String large = "片段".repeat(8_000);
        assertTrue(client.request(clientSide, TEXT, large).queued());
        deliver();
        assertEquals(large, serverMenu.text);
        assertEquals(ActionStatus.APPLIED, clientSide.lastActionResult().status());
        assertEquals(2, clientSide.lastActionResult().sequence());
        step();
        assertEquals(large, clientMenu.text);

        assertTrue(client.request(clientSide, ADD, -1).queued());
        deliver();
        assertEquals(ActionStatus.REJECTED, clientSide.lastActionResult().status());
        assertEquals(5, serverMenu.counter);
        assertFalse(client.sending(clientSide));
    }

    @Test
    void undeclaredActionsAreRefusedLocally() {
        var clientSide = open(new Menu(), new Menu()).get(1);
        var refusal = client.request(clientSide, UNDECLARED, 1);
        assertFalse(refusal.queued());
        assertEquals(ActionFailure.UNKNOWN_ACTION, refusal.failure());
        assertEquals(0, clientSide.lastActionResult().sequence());
        assertTrue(toServer.isEmpty());
    }

    @Test
    void mismatchedDeclarationsFailTheClientInsteadOfSyncing() {
        var serverBinding = binding(new Menu());
        var clientMenu = new Menu();
        var clientSide = new TestBindings.Binding<Menu, Player>(clientMenu, SCHEMA, MenuSyncOptions.DEFAULT, 7);
        this.clientMenu = clientMenu;
        clientBinding = clientSide;
        player.openMenu = serverBinding.menu();
        sessions.opened(player, serverBinding.menu(), serverBinding);
        deliver();
        assertEquals(SyncStatus.FAILED, clientSide.status());
        assertEquals(
                "Menu schema or transport options mismatch",
                clientSide.statistics().failure());
        assertFalse(clientSide.hasSnapshot());
    }

    @Test
    void closingTheMenuEndsBothSides() {
        var bindings = open(new Menu(), new Menu());
        clientMenu = null;
        client.tick();
        deliver();
        assertEquals(SyncStatus.CLOSED, bindings.get(0).status());
        assertEquals(SyncStatus.CLOSED, bindings.get(1).status());
    }

    @Test
    void aPlayerWhoLeftLosesTheSession() {
        var bindings = open(new Menu(), new Menu());
        player.present = false;
        step();
        assertEquals(SyncStatus.CLOSED, bindings.get(0).status());
    }

    @Test
    void stoppingTheServerEndsItsSessions() {
        var bindings = open(new Menu(), new Menu());
        sessions.stopped(server);
        assertEquals(SyncStatus.CLOSED, bindings.get(0).status());
        assertTrue(warnings.isEmpty(), () -> String.join("\n", warnings));
    }
}
