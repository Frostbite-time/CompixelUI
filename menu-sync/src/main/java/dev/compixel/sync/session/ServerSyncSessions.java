package dev.compixel.sync.session;

import dev.compixel.sync.SyncException;
import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionQueue;
import dev.compixel.sync.action.ActionResult;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.action.RequestWindow;
import dev.compixel.sync.transport.MessageAssembly;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The server side of menu synchronization: one session per player, from the menu opening until it closes. A session
 * sends the binding's state right behind its bootstrap, at most a window of unacknowledged batches at a time, and
 * validates, assembles and applies the client's actions. Use it on the server thread: pass it menu openings, every
 * message from clients, each server tick and the server stopping.
 */
public final class ServerSyncSessions<P> {
    /** What the sessions need from the server. Every call happens on the server thread. */
    public interface Host<P> {
        UUID id(P player);

        /** The player's server, compared by identity. */
        Object server(P player);

        /** The current tick of the player's server. */
        long tick(P player);

        /** Whether the player is still its server's player; one who left or respawned is not. */
        boolean current(P player);

        /** Whether the player's client handles the bootstrap, control and data messages. */
        boolean channelsAvailable(P player);

        /** The menu the player has open, compared by identity. */
        Object openMenu(P player);

        /** Whether the player may act on the open menu, for example alive, not spectating, the menu still valid. */
        boolean mayAct(P player);

        /** The codec context of the player's side of the server, for menu values and actions. */
        CodecScope codecs(P player);

        /** Sends a message to the player's client, behind everything sent to it before. */
        void send(P player, SyncMessage message);

        SyncLog log();
    }

    private static final class ActionInput {
        MessageAssembly assembly;
        long sequence;
        String action;
        int total;

        void clear() {
            if (assembly != null) assembly.close();
            assembly = null;
            action = null;
        }
    }

    private static final class Session<P> {
        final P player;
        final SyncBinding<?, P, ?> binding;
        final UUID nonce, token;
        final ActionInput input = new ActionInput();
        long request;
        RequestWindow actions;

        Session(P player, SyncBinding<?, P, ?> binding) {
            this.player = player;
            this.binding = binding;
            nonce = UUID.randomUUID();
            token = UUID.randomUUID();
        }
    }

    private static final SecureRandom STEPS = new SecureRandom();

    private final Host<P> host;
    private final Map<UUID, Session<P>> sessions = new HashMap<>();

    public ServerSyncSessions(Host<P> host) {
        this.host = Objects.requireNonNull(host);
    }

    /**
     * Starts the session for a menu the player opened, and sends its state right behind the bootstrap. Call it after
     * the client has been told to open the menu, so that the menu exists there when its state arrives; a client that
     * opens it later asks for the state again.
     */
    public void opened(P player, Object menu, SyncBinding<?, P, ?> binding) {
        if (!host.channelsAvailable(player) || binding.menu() != menu) return;
        var old = sessions.remove(host.id(player));
        if (old != null) {
            old.input.clear();
            if (old.binding != binding) old.binding.close();
        }
        var session = new Session<>(player, binding);
        sessions.put(host.id(player), session);
        start(session);
    }

    public void receive(P player, SyncMessage.Control message) {
        var session = sessions.get(host.id(player));
        if (!matches(session, message)) return;
        if (message.kind() == SyncMessage.Control.CLOSE) {
            end(session);
            return;
        }
        if (host.openMenu(player) != session.binding.menu()) return;
        if (message.kind() == SyncMessage.Control.RESTART) {
            start(session);
            return;
        }
        try {
            session.binding.acknowledge(message.revision(), message.batch());
        } catch (RuntimeException failure) {
            fail(session, failure);
        }
    }

    public void receive(P player, SyncMessage.ActionRequest message) {
        var session = actionSession(player, message);
        if (session == null) return;
        if (!admit(session, message.sequence(), message.action())) return;
        if (session.input.assembly != null) {
            reject(
                    session,
                    session.input.sequence,
                    session.input.action,
                    ActionFailure.MALFORMED,
                    -1,
                    -1,
                    "Interrupted action assembly");
            session.input.clear();
            reject(
                    session,
                    message.sequence(),
                    message.action(),
                    ActionFailure.MALFORMED,
                    -1,
                    -1,
                    "Action overtook unfinished fragments");
            return;
        }
        if (message.data().length > ActionQueue.FRAGMENT_BYTES) {
            reject(
                    session,
                    message.sequence(),
                    message.action(),
                    ActionFailure.MALFORMED,
                    message.data().length,
                    ActionQueue.FRAGMENT_BYTES,
                    "Inline action exceeds the fragment size");
            return;
        }
        reply(session, message.sequence(), apply(session, message.action(), message.data()));
    }

    public void receive(P player, SyncMessage.ActionFragment message) {
        var session = actionSession(player, message);
        if (session == null) return;
        var input = session.input;
        if (input.assembly == null || input.sequence != message.sequence()) {
            if (message.offset() != 0 || !admit(session, message.sequence(), message.action())) return;
            if (input.assembly != null) {
                reject(
                        session,
                        input.sequence,
                        input.action,
                        ActionFailure.MALFORMED,
                        -1,
                        -1,
                        "Interrupted action assembly");
                input.clear();
            }
            int limit = session.binding.actionLimit(message.action());
            if (limit == 0 || message.total() > limit) {
                reject(
                        session,
                        message.sequence(),
                        message.action(),
                        limit == 0 ? ActionFailure.UNKNOWN_ACTION : ActionFailure.BODY_BYTES,
                        message.total(),
                        limit,
                        "Action body exceeds declaration");
                return;
            }
            input.assembly = new MessageAssembly(message.total(), limit);
            input.sequence = message.sequence();
            input.action = message.action();
            input.total = message.total();
        }
        try {
            if (!input.action.equals(message.action())
                    || input.total != message.total()
                    || message.data().length > ActionQueue.FRAGMENT_BYTES)
                throw new SyncException("Invalid action fragment metadata/size");
            byte[] data = input.assembly.append(message.offset(), message.data());
            if (data == null) {
                host.send(
                        session.player,
                        new SyncMessage.ActionProgress(
                                session.binding.menuId(),
                                session.nonce,
                                session.request,
                                session.token,
                                message.sequence(),
                                input.assembly.received()));
                return;
            }
            input.clear();
            if (mayAct(session)) reply(session, message.sequence(), apply(session, message.action(), data));
            else
                reject(
                        session,
                        message.sequence(),
                        message.action(),
                        ActionFailure.REJECTED,
                        -1,
                        -1,
                        "Menu became unavailable");
        } catch (RuntimeException invalid) {
            input.clear();
            reject(
                    session,
                    message.sequence(),
                    message.action(),
                    ActionFailure.MALFORMED,
                    -1,
                    -1,
                    SyncBinding.concise(invalid.getMessage()));
        }
    }

    /** Ends stale sessions of {@code server} and sends pending state. */
    public void tick(Object server) {
        var iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            var session = iterator.next();
            if (host.server(session.player) != server) continue;
            if (!host.current(session.player) || host.openMenu(session.player) != session.binding.menu()) {
                session.input.clear();
                session.binding.close();
                iterator.remove();
                continue;
            }
            pump(session, host.tick(session.player));
        }
    }

    /** Ends the sessions of a stopped server. */
    public void stopped(Object server) {
        sessions.values().removeIf(session -> {
            if (host.server(session.player) != server) return false;
            session.input.clear();
            session.binding.close();
            return true;
        });
    }

    /**
     * Starts the state over: a new request number, a fresh snapshot right behind a new bootstrap, and a new action
     * window for the client's new queue. Request numbers grow by unpredictable steps, so a client can only ask to start
     * over a bootstrap it has received, never several ahead in one burst.
     */
    private void start(Session<P> session) {
        session.input.clear();
        session.request += 1 + STEPS.nextInt(1 << 30);
        session.actions = new RequestWindow(session.token, ActionQueue.ACTIONS_PER_TICK);
        session.binding.serverStart();
        host.send(
                session.player,
                new SyncMessage.Bootstrap(
                        session.binding.menuId(),
                        session.nonce,
                        session.request,
                        session.token,
                        session.binding.schemaId(),
                        session.binding.fingerprint()));
        pump(session, host.tick(session.player));
    }

    private void end(Session<P> session) {
        session.input.clear();
        session.binding.close();
        sessions.remove(host.id(session.player));
    }

    private boolean matches(Session<P> session, SyncMessage message) {
        return session != null
                && session.binding.menuId() == message.menu()
                && session.nonce.equals(message.nonce())
                && session.request == message.request()
                && session.token.equals(message.session());
    }

    private Session<P> actionSession(P player, SyncMessage message) {
        var session = sessions.get(host.id(player));
        if (!matches(session, message)
                || host.openMenu(player) != session.binding.menu()
                || session.binding.status() == SyncStatus.CLOSED
                || session.binding.status() == SyncStatus.FAILED) return null;
        return session;
    }

    private boolean admit(Session<P> session, long sequence, String action) {
        var admission = session.actions.admit(session.token, sequence, host.tick(session.player));
        if (admission == RequestWindow.Admission.STALE) return false;
        if (admission == RequestWindow.Admission.THROTTLED) {
            reject(
                    session,
                    sequence,
                    action,
                    ActionFailure.THROTTLED,
                    -1,
                    ActionQueue.ACTIONS_PER_TICK,
                    "Per-tick action admission exceeded");
            return false;
        }
        if (!mayAct(session)) {
            reject(
                    session,
                    sequence,
                    action,
                    ActionFailure.REJECTED,
                    -1,
                    -1,
                    "Menu is not ready or player may not act");
            return false;
        }
        return true;
    }

    private boolean mayAct(Session<P> session) {
        return host.mayAct(session.player) && session.binding.revision() >= 1;
    }

    private ActionResult apply(Session<P> session, String action, byte[] data) {
        try {
            return session.binding.applyAction(action, data, session.player, host.codecs(session.player));
        } catch (RuntimeException failure) {
            host.log()
                    .warn(
                            "Menu action handler failed: schema=" + session.binding.schemaId() + ", action=" + action,
                            failure);
            return new ActionResult(
                    0, action, ActionStatus.REJECTED, ActionFailure.REJECTED, -1, -1, "Consumer handler failed");
        }
    }

    private void reply(Session<P> session, long sequence, ActionResult result) {
        session.binding.warnAction(
                new ActionResult(
                        sequence,
                        result.action(),
                        result.status(),
                        result.failure(),
                        result.actual(),
                        result.limit(),
                        result.detail()),
                host.tick(session.player));
        host.send(
                session.player,
                new SyncMessage.ActionReply(
                        session.binding.menuId(),
                        session.nonce,
                        session.request,
                        session.token,
                        sequence,
                        result.status(),
                        result.failure(),
                        result.actual(),
                        result.limit(),
                        result.detail()));
    }

    private void reject(
            Session<P> session,
            long sequence,
            String action,
            ActionFailure reason,
            long actual,
            long limit,
            String detail) {
        var status = reason == ActionFailure.THROTTLED
                ? ActionStatus.THROTTLED
                : reason == ActionFailure.REJECTED ? ActionStatus.REJECTED : ActionStatus.INVALID;
        reply(session, sequence, new ActionResult(sequence, action, status, reason, actual, limit, detail));
    }

    private void pump(Session<P> session, long tick) {
        try {
            if (session.binding.status() == SyncStatus.FAILED || session.binding.status() == SyncStatus.CLOSED) return;
            session.binding.serverPump(
                    host.codecs(session.player),
                    tick,
                    batch -> host.send(
                            session.player,
                            new SyncMessage.Data(
                                    session.binding.menuId(),
                                    session.nonce,
                                    session.request,
                                    session.token,
                                    batch,
                                    "")));
        } catch (RuntimeException failure) {
            fail(session, failure);
        }
    }

    private void fail(Session<P> session, RuntimeException failure) {
        session.input.clear();
        session.binding.fail(failure.getMessage() == null ? "Sync failure" : failure.getMessage());
        host.log().warn("Menu synchronization failed for " + session.binding.schemaId() + ": " + failure, null);
        host.send(
                session.player,
                new SyncMessage.Data(
                        session.binding.menuId(),
                        session.nonce,
                        session.request,
                        session.token,
                        null,
                        "Menu synchronization failed; reopen the menu"));
    }
}
