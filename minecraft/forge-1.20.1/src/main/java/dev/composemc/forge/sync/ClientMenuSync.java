package dev.composemc.forge.sync;

import dev.composemc.sync.*;
import dev.composemc.sync.action.ActionFailure;
import dev.composemc.sync.action.ActionQueue;
import dev.composemc.sync.action.ActionSubmission;
import dev.composemc.sync.transport.TokenBucket;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;

/** Client adapter only. Common registration does not initialize this class on a server. */
public final class ClientMenuSync {
    private static MenuSync<?> active;
    private static UUID nonce, serverSession = SyncPayloads.NO_SESSION;
    private static long request, tick, lastData, waitingSince;

    private record Bootstrap(SyncPayloads.Bootstrap message, long received) {}

    private static Bootstrap bootstrap;
    private static Object connectionIdentity;
    private static TokenBucket bandwidth;
    private static ActionQueue actions;

    static long clock() {
        return tick;
    }

    private static void clearActions() {
        if (actions != null) actions.clear();
        actions = null;
    }

    private static void checkConnection(Minecraft mc) {
        if (connectionIdentity == mc.getConnection()) return;
        if (active != null) active.close();
        active = null;
        clearActions();
        bootstrap = null;
        request = 0;
        nonce = null;
        serverSession = SyncPayloads.NO_SESSION;
        connectionIdentity = mc.getConnection();
        bandwidth = null;
    }

    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        Minecraft mc = Minecraft.getInstance();
        checkConnection(mc);
        MenuSync<?> next =
                mc.player != null && mc.player.containerMenu instanceof SyncedMenu synced ? synced.menuSync() : null;
        if (next != active) replaceActive(next, mc);
        if (active != null && request == 0 && bootstrap != null) attachBootstrap(active, bootstrap.message());
        if (bootstrap != null
                && tick - bootstrap.received() > bootstrap.message().limits().timeoutTicks()) {
            bootstrap = null;
        }
        if (active != null && request == 0) {
            if (!hasChannels()) active.fail("Server does not support composemc menu synchronization");
            else if (tick - waitingSince > active.limits.timeoutTicks())
                active.fail("Server did not start menu synchronization");
        }
        if (active != null
                && request > 0
                && active.status() == MenuSync.Status.SYNCING
                && (active.expired(tick) || tick - lastData > active.limits.timeoutTicks()))
            active.fail("Menu synchronization timed out");
        if (actions != null && active != null) {
            var expired = actions.expired(tick);
            if (expired != null) {
                active.actionResult(
                        new MenuSync.ActionResult(
                                expired.sequence(),
                                expired.action(),
                                MenuSync.ActionStatus.EXPIRED,
                                expired.failure(),
                                expired.elapsed(),
                                expired.limit(),
                                "Action deadline exceeded; reopen the menu"),
                        tick);
                active.fail("Menu action timed out; reopen the menu");
            }
        }
        if (active != null && (active.status() == MenuSync.Status.FAILED || active.status() == MenuSync.Status.CLOSED))
            clearActions();
        pumpActions();
    }

    private static boolean hasChannels() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && MenuSyncNetworking.hasChannel(connection.getConnection());
    }

    private static void replaceActive(MenuSync<?> next, Minecraft mc) {
        closeActive(mc);
        active = next;
        clearActions();
        nonce = null;
        request = 0;
        serverSession = SyncPayloads.NO_SESSION;
        waitingSince = tick;
    }

    private static void closeActive(Minecraft mc) {
        if (active == null) return;
        if (request > 0
                && mc.getConnection() != null
                && MenuSyncNetworking.hasChannel(mc.getConnection().getConnection()))
            MenuSyncNetworking.sendToServer(new SyncPayloads.Control(
                    SyncPayloads.CLOSE, active.model.containerId, nonce, request, serverSession, "", "", 0, 0));
        active.close();
    }

    static void receiveBootstrap(SyncPayloads.Bootstrap message) {
        Minecraft mc = Minecraft.getInstance();
        checkConnection(mc);
        bootstrap = new Bootstrap(message, tick);
        MenuSync<?> current =
                mc.player != null && mc.player.containerMenu instanceof SyncedMenu synced ? synced.menuSync() : null;
        if (current != null && current.model.containerId == message.menu()) attachBootstrap(current, message);
    }

    private static void attachBootstrap(MenuSync<?> binding, SyncPayloads.Bootstrap message) {
        if (binding.model.containerId != message.menu()) return;
        if (!binding.schemaId().equals(message.schema())
                || !binding.fingerprint().equals(message.fingerprint())
                || !binding.limits.equals(message.limits())) {
            binding.fail("Menu schema or transport options mismatch");
            bootstrap = null;
            return;
        }
        if (active != binding) {
            closeActive(Minecraft.getInstance());
            active = binding;
            clearActions();
            request = 0;
        }
        if (request > 0) return;
        nonce = message.nonce();
        request = message.request();
        serverSession = message.session();
        lastData = tick;
        if (bandwidth == null)
            bandwidth = new TokenBucket(binding.options.actions().bandwidth(), tick);
        else bandwidth.configure(binding.options.actions().bandwidth(), tick);
        actions = new ActionQueue(binding.options.actions(), bandwidth);
        active.clientStart();
        bootstrap = null;
        MenuSyncNetworking.sendToServer(new SyncPayloads.Control(
                SyncPayloads.READY, message.menu(), nonce, request, serverSession, "", "", 0, 0));
    }
    /** Null means admitted to encoding, not yet queued. */
    static ActionSubmission admission(MenuSync<?> binding) {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Menu commands belong to the client game thread");
        if (binding.status() == MenuSync.Status.CLOSED) return ActionSubmission.rejected(ActionFailure.CLOSED, -1, -1);
        if (active != binding
                || mc.player == null
                || mc.player.containerMenu != binding.model
                || !binding.hasSnapshot()
                || actions == null) return ActionSubmission.rejected(ActionFailure.NOT_READY, -1, -1);
        if (!hasChannels()) return ActionSubmission.rejected(ActionFailure.TRANSPORT_UNAVAILABLE, -1, -1);
        return actions.admission();
    }

    static long remainingBytes(MenuSync<?> binding) {
        return active == binding && actions != null ? actions.remainingBytes() : 0;
    }

    static ActionSubmission sendAction(MenuSync<?> binding, String action, byte[] data) {
        var refusal = admission(binding);
        if (refusal != null) return refusal;
        var result = actions.offer(action, data, tick);
        if (result.queued()) pumpActions();
        return result;
    }

    static boolean sending(MenuSync<?> binding) {
        return active == binding && actions != null && actions.sending();
    }

    private static void pumpActions() {
        var mc = Minecraft.getInstance();
        if (active == null
                || actions == null
                || mc.getConnection() == null
                || mc.player == null
                || mc.player.containerMenu != active.model
                || !active.hasSnapshot()) return;
        actions.pump(tick, () -> mc.getConnection().getConnection().channel().isWritable(), part -> {
            if (part.single())
                MenuSyncNetworking.sendToServer(new MenuActionPayloads.Request(
                        active.model.containerId,
                        nonce,
                        request,
                        serverSession,
                        part.sequence(),
                        part.action(),
                        part.data()));
            else
                MenuSyncNetworking.sendToServer(new MenuActionPayloads.Fragment(
                        active.model.containerId,
                        nonce,
                        request,
                        serverSession,
                        part.sequence(),
                        part.action(),
                        part.total(),
                        part.offset(),
                        part.data()));
        });
    }

    static void receiveAction(MenuActionPayloads.Result message) {
        var mc = Minecraft.getInstance();
        if (active == null
                || actions == null
                || mc.player == null
                || mc.player.containerMenu != active.model
                || active.model.containerId != message.menu()
                || !message.nonce().equals(nonce)
                || message.request() != request
                || !message.session().equals(serverSession)) return;
        String action = actions.reply(message.sequence());
        if (action != null)
            active.actionResult(
                    new MenuSync.ActionResult(
                            message.sequence(),
                            action,
                            message.status(),
                            message.failure(),
                            message.actual(),
                            message.limit(),
                            message.detail()),
                    tick);
    }

    static void receive(SyncPayloads.Data message) {
        Minecraft mc = Minecraft.getInstance();
        if (active == null
                || mc.player == null
                || mc.player.containerMenu != active.model
                || active.model.containerId != message.menu()
                || !message.nonce().equals(nonce)
                || message.request() != request) {
            return;
        }
        receiveAttached(message);
    }

    private static void receiveAttached(SyncPayloads.Data message) {
        if (active.status() == MenuSync.Status.CLOSED || active.status() == MenuSync.Status.FAILED) return;
        if (!serverSession.equals(message.session())) return;
        if (message.batch() == null) {
            active.fail(message.failure());
            return;
        }
        lastData = tick;
        try {
            active.receive(message.batch(), tick);
            MenuSyncNetworking.sendToServer(new SyncPayloads.Control(
                    SyncPayloads.ACK,
                    message.menu(),
                    nonce,
                    request,
                    serverSession,
                    "",
                    "",
                    message.batch().revision(),
                    message.batch().index()));
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger()
                    .warn("Menu synchronization rejected for {}: {}", active.schemaId(), failure.toString());
            active.fail("Invalid menu synchronization data");
        }
    }

    private ClientMenuSync() {}
}
