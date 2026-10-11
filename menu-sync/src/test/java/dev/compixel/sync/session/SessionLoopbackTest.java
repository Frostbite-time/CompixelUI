package dev.compixel.sync.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionQueue;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncPublisher;
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
            .field("text", SyncCodecs.STRING, m -> m.text, (m, v) -> m.text = v)
            .field("counter", SyncCodecs.INT, m -> m.counter, (m, v) -> m.counter = v)
            .build();
    static final TestBindings.Action<Menu, Player, Integer> ADD =
            new TestBindings.Action<>("add", SyncCodecs.INT, (menu, player, value) -> {
                if (value <= 0) return false;
                menu.counter += value;
                return true;
            });
    static final TestBindings.Action<Menu, Player, String> TEXT =
            new TestBindings.Action<>("text", SyncCodecs.STRING, 8 * 1024 * 1024, (menu, player, value) -> {
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

        public boolean channelsAvailable(Player p) {
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
        return new TestBindings.Binding<Menu, Player>(menu, SCHEMA, 7)
                .action(ADD)
                .action(TEXT);
    }

    private void toServer(SyncMessage up) {
        if (up instanceof SyncMessage.Control control) sessions.receive(player, control);
        else if (up instanceof SyncMessage.ActionRequest request) sessions.receive(player, request);
        else if (up instanceof SyncMessage.ActionFragment fragment) sessions.receive(player, fragment);
        else fail("Unexpected client message " + up);
    }

    private void toClient(SyncMessage down) {
        if (down instanceof SyncMessage.Bootstrap bootstrap) client.receive(bootstrap);
        else if (down instanceof SyncMessage.Data data) client.receive(data);
        else if (down instanceof SyncMessage.ActionProgress progress) client.receive(progress);
        else if (down instanceof SyncMessage.ActionReply reply) client.receive(reply);
        else fail("Unexpected server message " + down);
    }

    /** Delivers queued messages both ways until none are left. */
    private void deliver() {
        for (int i = 0; i < 100_000 && (!toClient.isEmpty() || !toServer.isEmpty()); i++) {
            var up = toServer.poll();
            if (up != null) toServer(up);
            var down = toClient.poll();
            if (down != null) toClient(down);
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
        assertTrue(clientSide.hasSnapshot());
        assertEquals("server state", clientMenu.text);
        assertEquals(SyncStatus.READY, serverSide.status());

        assertTrue(client.request(clientSide, ADD, 5).queued());
        deliver();
        assertEquals(5, serverMenu.counter);
        assertEquals(ActionStatus.APPLIED, clientSide.lastActionResult().status());
        assertEquals(1, clientSide.lastActionResult().sequence());
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
        assertTrue(warnings.stream().noneMatch(w -> w.contains("failed")), () -> String.join("\n", warnings));
    }

    @Test
    void theFirstStateFollowsTheBootstrapWithoutWaitingForTheClient() {
        var serverMenu = new Menu();
        serverMenu.text = "opened";
        var clientMenu = new Menu();
        this.clientMenu = clientMenu;
        clientBinding = binding(clientMenu);
        player.openMenu = serverMenu;
        sessions.opened(player, serverMenu, binding(serverMenu));
        // Nothing has gone back to the server yet, and the state is already on its way.
        assertTrue(toServer.isEmpty());
        var sent = List.copyOf(toClient);
        assertInstanceOf(SyncMessage.Bootstrap.class, sent.get(0));
        assertInstanceOf(SyncMessage.Data.class, sent.get(1));
        while (!toClient.isEmpty()) toClient(toClient.poll());
        assertTrue(clientBinding.hasSnapshot());
        assertEquals("opened", clientMenu.text);
        assertTrue(toServer.stream().allMatch(m -> m instanceof SyncMessage.Control c && c.kind() == 0));
    }

    @Test
    void aMenuThatOpensAfterItsStateArrivedAsksForItAgain() {
        var serverMenu = new Menu();
        serverMenu.text = "x".repeat(3 * SyncPublisher.WINDOW_BYTES / 2);
        serverMenu.counter = 3;
        var serverBinding = binding(serverMenu);
        player.openMenu = serverMenu;
        sessions.opened(player, serverMenu, serverBinding);
        // The client's menu does not exist yet: its bootstrap and first batches arrive and are dropped.
        while (!toClient.isEmpty()) toClient(toClient.poll());
        assertTrue(toServer.isEmpty());
        var clientMenu = new Menu();
        this.clientMenu = clientMenu;
        var clientSide = binding(clientMenu);
        clientBinding = clientSide;
        for (int i = 0; i < 10 && !clientSide.hasSnapshot(); i++) step();
        assertTrue(clientSide.hasSnapshot());
        assertEquals(serverMenu.text, clientMenu.text);
        assertEquals(3, clientMenu.counter);
        assertTrue(client.request(clientSide, ADD, 2).queued());
        deliver();
        assertEquals(5, serverMenu.counter);
        assertEquals(ActionStatus.APPLIED, clientSide.lastActionResult().status());
    }

    @Test
    void aClientCanOnlyAskToStartOverABootstrapItReceived() {
        var serverMenu = new Menu();
        serverMenu.text = "state";
        player.openMenu = serverMenu;
        sessions.opened(player, serverMenu, binding(serverMenu));
        var first = (SyncMessage.Bootstrap) toClient.peekFirst();
        toClient.clear();
        // Asking for this bootstrap and the request numbers after it in one burst starts over once.
        for (long ahead = 0; ahead < 4; ahead++)
            sessions.receive(
                    player,
                    new SyncMessage.Control(
                            SyncMessage.Control.RESTART,
                            first.menu(),
                            first.nonce(),
                            first.request() + ahead,
                            first.session(),
                            0,
                            0));
        assertEquals(
                1,
                toClient.stream()
                        .filter(m -> m instanceof SyncMessage.Bootstrap)
                        .count());
    }

    @Test
    void largeStatesAndActionsArriveAtAnySize() {
        var serverMenu = new Menu();
        var clientMenu = new Menu();
        var bindings = open(serverMenu, clientMenu);
        var clientSide = bindings.get(1);
        // About 3 MB each way, larger than any Minecraft payload and many windows long.
        String state = "中文😀".repeat(300_000);
        serverMenu.text = state;
        for (int i = 0; i < 50 && !clientMenu.text.equals(state); i++) step();
        assertEquals(state, clientMenu.text);
        String action = "片段".repeat(500_000);
        assertTrue(client.request(clientSide, TEXT, action).queued());
        for (int i = 0; i < 50 && !serverMenu.text.equals(action); i++) step();
        assertEquals(action, serverMenu.text);
        assertEquals(ActionStatus.APPLIED, clientSide.lastActionResult().status());
        assertFalse(client.sending(clientSide));
    }

    @Test
    void actionsWaitForConfirmationBeyondTheirWindow() {
        var clientSide = open(new Menu(), new Menu()).get(1);
        String action = "x".repeat(2 * ActionQueue.WINDOW_BYTES);
        assertTrue(client.request(clientSide, TEXT, action).queued());
        long sent = toServer.stream()
                .mapToLong(m -> m instanceof SyncMessage.ActionFragment f ? f.data().length : 0)
                .sum();
        assertEquals(ActionQueue.WINDOW_BYTES, sent);
        deliver();
        assertEquals(ActionStatus.APPLIED, clientSide.lastActionResult().status());
    }

    @Test
    void aClientThatRejectsTheStateStopsTheServer() {
        var serverMenu = new Menu();
        serverMenu.text = "x".repeat(3 * SyncPublisher.WINDOW_BYTES / 2);
        var serverBinding = binding(serverMenu);
        var clientMenu = new Menu();
        this.clientMenu = clientMenu;
        // The client's setter throws, so its receiver rejects the snapshot.
        SyncSchema<Menu> failing = SyncSchema.<Menu>builder("test:loopback", 1)
                .field("text", SyncCodecs.STRING, m -> m.text, (m, v) -> {
                    throw new IllegalStateException("setter");
                })
                .field("counter", SyncCodecs.INT, m -> m.counter, (m, v) -> m.counter = v)
                .build();
        var clientSide = new TestBindings.Binding<Menu, Player>(clientMenu, failing, 7)
                .action(ADD)
                .action(TEXT);
        clientBinding = clientSide;
        player.openMenu = serverMenu;
        sessions.opened(player, serverMenu, serverBinding);
        for (int i = 0; i < 10; i++) step();
        assertEquals(SyncStatus.FAILED, clientSide.status());
        assertEquals(SyncStatus.CLOSED, serverBinding.status());
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
    void mismatchedDeclarationsFailTheClientAndStopTheServer() {
        var serverBinding = binding(new Menu());
        var clientMenu = new Menu();
        var clientSide = new TestBindings.Binding<Menu, Player>(clientMenu, SCHEMA, 7);
        this.clientMenu = clientMenu;
        clientBinding = clientSide;
        player.openMenu = serverBinding.menu();
        sessions.opened(player, serverBinding.menu(), serverBinding);
        deliver();
        assertEquals(SyncStatus.FAILED, clientSide.status());
        assertEquals("Menu schema mismatch", clientSide.statistics().failure());
        assertFalse(clientSide.hasSnapshot());
        assertEquals(SyncStatus.CLOSED, serverBinding.status());
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

    @Test
    void aSessionWithoutTrafficNeverExpires() {
        var serverMenu = new Menu();
        var bindings = open(serverMenu, new Menu());
        for (int i = 0; i < 10_000; i++) {
            serverTick++;
            sessions.tick(server);
            client.tick();
        }
        deliver();
        assertEquals(SyncStatus.READY, bindings.get(0).status());
        assertEquals(SyncStatus.READY, bindings.get(1).status());
        serverMenu.counter = 9;
        step();
        assertEquals(9, ((Menu) bindings.get(1).menu()).counter);
    }
}
