package dev.compixel.sync.session;

import dev.compixel.sync.SizeLimitException;
import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionQueue;
import dev.compixel.sync.action.ActionResult;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.action.ActionSubmission;
import dev.compixel.sync.transport.TokenBucket;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The client side of menu synchronization. It follows the menu the player has open, attaches its binding to the
 * server's session, applies the state the server sends and sends the binding's actions. Use it on the client's game
 * thread: tick it once per client tick and pass it every message the server sends.
 */
public final class ClientSyncSession {
    /** What the session needs from the client. Every call happens on the client's game thread. */
    public interface Host {
        /** The current connection, compared by identity; a new one resets the session. Null while disconnected. */
        Object connection();

        /** The menu the player has open, compared by identity, or null. */
        Object openMenu();

        /** The binding of the menu the player has open, or null when it has none. */
        SyncBinding<?, ?, ?> openBinding();

        /** Whether the server handles every menu synchronization message. */
        boolean channelsAvailable();

        /** Whether the server can receive a control message, which closes its session. */
        boolean controlAvailable();

        /** Whether the connection takes more data now. */
        boolean writable();

        boolean onGameThread();

        /** The client's codec context for menu values and actions. */
        CodecScope codecs();

        void send(SyncMessage message);

        SyncLog log();
    }

    private record Bootstrap(SyncMessage.Bootstrap message, long received) {}

    private final Host host;
    private SyncBinding<?, ?, ?> active;
    private UUID nonce, serverSession = SyncMessage.NO_SESSION;
    private long request, tick, lastData, waitingSince;
    private Bootstrap bootstrap;
    private Object connection;
    private TokenBucket bandwidth;
    private ActionQueue actions;

    public ClientSyncSession(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    public void tick() {
        tick++;
        checkConnection();
        var next = host.openBinding();
        if (next != active) replaceActive(next);
        if (active != null && request == 0 && bootstrap != null) attach(active, bootstrap.message());
        if (bootstrap != null
                && tick - bootstrap.received() > bootstrap.message().limits().timeoutTicks()) bootstrap = null;
        if (active != null && request == 0) {
            if (!host.channelsAvailable()) active.fail("Server does not support compixel menu synchronization");
            else if (tick - waitingSince > active.limits().timeoutTicks())
                active.fail("Server did not start menu synchronization");
        }
        if (active != null
                && request > 0
                && active.status() == SyncStatus.SYNCING
                && (active.expired(tick) || tick - lastData > active.limits().timeoutTicks()))
            active.fail("Menu synchronization timed out");
        if (actions != null && active != null) {
            var expired = actions.expired(tick);
            if (expired != null) {
                active.actionResult(
                        new ActionResult(
                                expired.sequence(),
                                expired.action(),
                                ActionStatus.EXPIRED,
                                expired.failure(),
                                expired.elapsed(),
                                expired.limit(),
                                "Action deadline exceeded; reopen the menu"),
                        tick);
                active.fail("Menu action timed out; reopen the menu");
            }
        }
        if (active != null && (active.status() == SyncStatus.FAILED || active.status() == SyncStatus.CLOSED))
            clearActions();
        pumpActions();
    }

    public void receive(SyncMessage.Bootstrap message) {
        checkConnection();
        bootstrap = new Bootstrap(message, tick);
        var current = host.openBinding();
        if (current != null && current.menuId() == message.menu()) attach(current, message);
    }

    public void receive(SyncMessage.Data message) {
        if (active == null
                || host.openMenu() != active.menu()
                || active.menuId() != message.menu()
                || !message.nonce().equals(nonce)
                || message.request() != request) return;
        if (active.status() == SyncStatus.CLOSED || active.status() == SyncStatus.FAILED) return;
        if (!serverSession.equals(message.session())) return;
        if (message.batch() == null) {
            active.fail(message.failure());
            return;
        }
        lastData = tick;
        try {
            active.receive(host.codecs(), message.batch(), tick);
            host.send(new SyncMessage.Control(
                    SyncMessage.Control.ACK,
                    message.menu(),
                    nonce,
                    request,
                    serverSession,
                    message.batch().revision(),
                    message.batch().index()));
        } catch (RuntimeException failure) {
            host.log().warn("Menu synchronization rejected for " + active.schemaId() + ": " + failure, null);
            active.fail("Invalid menu synchronization data");
        }
    }

    public void receive(SyncMessage.ActionReply message) {
        if (active == null
                || actions == null
                || host.openMenu() != active.menu()
                || active.menuId() != message.menu()
                || !message.nonce().equals(nonce)
                || message.request() != request
                || !message.session().equals(serverSession)) return;
        String action = actions.reply(message.sequence());
        if (action != null)
            active.actionResult(
                    new ActionResult(
                            message.sequence(),
                            action,
                            message.status(),
                            message.failure(),
                            message.actual(),
                            message.limit(),
                            message.detail()),
                    tick);
    }

    /**
     * Queues {@code action} for the server. {@code queued()} means accepted into the local queue, never applied by the
     * server; every refusal also reaches the binding's action-result listeners.
     */
    public <M, P, V> ActionSubmission request(
            SyncBinding<M, P, ?> binding, SyncAction<? super M, P, V> action, V value) {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(action);
        if (binding.registered(action.id()) != action)
            return refuse(
                    binding,
                    action.id(),
                    ActionFailure.UNKNOWN_ACTION,
                    -1,
                    -1,
                    "Action was not registered on this menu");
        var admission = admission(binding);
        if (admission != null)
            return refuse(
                    binding,
                    action.id(),
                    admission.failure(),
                    admission.actual(),
                    admission.limit(),
                    "Action admission refused");
        int available = (int) Math.min(Integer.MAX_VALUE, actions.remainingBytes());
        try {
            byte[] data;
            try (var ignored = host.codecs().enter()) {
                data = action.encode(value, available);
            }
            var refusal = admission(binding);
            var result = refusal != null ? refusal : actions.offer(action.id(), data, tick);
            if (!result.queued())
                return refuse(
                        binding,
                        action.id(),
                        result.failure(),
                        result.actual(),
                        result.limit(),
                        "Action queue refused");
            pumpActions();
            return result;
        } catch (SizeLimitException oversized) {
            boolean queue = available < action.maximumBytes();
            long maxQueued = binding.options().actions().maxQueuedBytes();
            return refuse(
                    binding,
                    action.id(),
                    queue ? ActionFailure.QUEUE_BYTES : ActionFailure.BODY_BYTES,
                    queue ? maxQueued - available + oversized.actual() : oversized.actual(),
                    queue ? maxQueued : action.maximumBytes(),
                    oversized.getMessage());
        } catch (IOException invalid) {
            return refuse(binding, action.id(), ActionFailure.CODEC, -1, -1, invalid.getMessage());
        }
    }

    /** Whether {@code binding} still has actions on their way to the server. */
    public boolean sending(SyncBinding<?, ?, ?> binding) {
        return active == binding && actions != null && actions.sending();
    }

    private ActionSubmission refuse(
            SyncBinding<?, ?, ?> binding, String action, ActionFailure reason, long actual, long limit, String detail) {
        binding.actionResult(
                new ActionResult(
                        0,
                        action,
                        reason == ActionFailure.BODY_BYTES || reason == ActionFailure.CODEC
                                ? ActionStatus.INVALID
                                : ActionStatus.REJECTED,
                        reason,
                        actual,
                        limit,
                        SyncBinding.concise(detail)),
                tick);
        return ActionSubmission.rejected(reason, actual, limit);
    }

    /** Null means admitted to encoding, not yet queued. */
    private ActionSubmission admission(SyncBinding<?, ?, ?> binding) {
        if (!host.onGameThread()) throw new IllegalStateException("Menu commands belong to the client game thread");
        if (binding.status() == SyncStatus.CLOSED) return ActionSubmission.rejected(ActionFailure.CLOSED, -1, -1);
        if (active != binding || host.openMenu() != binding.menu() || !binding.hasSnapshot() || actions == null)
            return ActionSubmission.rejected(ActionFailure.NOT_READY, -1, -1);
        if (!host.channelsAvailable()) return ActionSubmission.rejected(ActionFailure.TRANSPORT_UNAVAILABLE, -1, -1);
        return actions.admission();
    }

    private void checkConnection() {
        var current = host.connection();
        if (connection == current) return;
        if (active != null) active.close();
        active = null;
        clearActions();
        bootstrap = null;
        request = 0;
        nonce = null;
        serverSession = SyncMessage.NO_SESSION;
        connection = current;
        bandwidth = null;
    }

    private void replaceActive(SyncBinding<?, ?, ?> next) {
        closeActive();
        active = next;
        clearActions();
        nonce = null;
        request = 0;
        serverSession = SyncMessage.NO_SESSION;
        waitingSince = tick;
    }

    private void closeActive() {
        if (active == null) return;
        if (request > 0 && host.controlAvailable())
            host.send(new SyncMessage.Control(
                    SyncMessage.Control.CLOSE, active.menuId(), nonce, request, serverSession, 0, 0));
        active.close();
    }

    private void attach(SyncBinding<?, ?, ?> binding, SyncMessage.Bootstrap message) {
        if (binding.menuId() != message.menu()) return;
        if (!binding.schemaId().equals(message.schema())
                || !binding.fingerprint().equals(message.fingerprint())
                || !binding.limits().equals(message.limits())) {
            binding.fail("Menu schema or transport options mismatch");
            bootstrap = null;
            return;
        }
        if (active != binding) {
            closeActive();
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
            bandwidth = new TokenBucket(binding.options().actions().bandwidth(), tick);
        else bandwidth.configure(binding.options().actions().bandwidth(), tick);
        actions = new ActionQueue(binding.options().actions(), bandwidth);
        active.clientStart();
        bootstrap = null;
        host.send(new SyncMessage.Control(
                SyncMessage.Control.READY, message.menu(), nonce, request, serverSession, 0, 0));
    }

    private void pumpActions() {
        if (active == null
                || actions == null
                || host.connection() == null
                || host.openMenu() != active.menu()
                || !active.hasSnapshot()) return;
        var binding = active;
        actions.pump(tick, host::writable, part -> {
            if (part.single())
                host.send(new SyncMessage.ActionRequest(
                        binding.menuId(), nonce, request, serverSession, part.sequence(), part.action(), part.data()));
            else
                host.send(new SyncMessage.ActionFragment(
                        binding.menuId(),
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

    private void clearActions() {
        if (actions != null) actions.clear();
        actions = null;
    }
}
