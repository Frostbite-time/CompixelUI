package dev.composemc.forge.sync;

import com.mojang.logging.LogUtils;
import dev.composemc.sync.*;
import dev.composemc.sync.action.ActionFailure;
import dev.composemc.sync.action.RequestWindow;
import dev.composemc.sync.transport.MessageAssembly;
import dev.composemc.sync.transport.TokenBucket;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Loader-facing implementation. All session/model work belongs to the server thread. */
public final class MenuSyncNetworking {
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

    private static final class StartGate {
        final long openedAt;
        boolean ready;

        StartGate(long openedAt) {
            this.openedAt = openedAt;
        }
    }

    private record Session(
            ServerPlayer player,
            MenuSync<?> binding,
            UUID nonce,
            long request,
            UUID token,
            RequestWindow actions,
            ActionInput input,
            StartGate start) {}

    private static final Map<UUID, Session> sessions = new HashMap<>();

    private record ConnectionBudget(net.minecraft.server.MinecraftServer server, TokenBucket bucket) {}
    // The connection survives both menu replacement and player respawn.
    private static final Map<net.minecraft.network.Connection, ConnectionBudget> bandwidth = new WeakHashMap<>();

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("5").optional();
        registrar.playToServer(SyncPayloads.Control.TYPE, SyncPayloads.Control.CODEC, MenuSyncNetworking::control);
        registrar.playToClient(SyncPayloads.Bootstrap.TYPE, SyncPayloads.Bootstrap.CODEC, (payload, context) -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientMenuSync.receiveBootstrap(payload);
        });
        registrar.playToClient(SyncPayloads.Data.TYPE, SyncPayloads.Data.CODEC, (payload, context) -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientMenuSync.receive(payload);
        });
        registrar.playToServer(
                MenuActionPayloads.Request.TYPE, MenuActionPayloads.Request.CODEC, MenuSyncNetworking::action);
        registrar.playToServer(
                MenuActionPayloads.Fragment.TYPE, MenuActionPayloads.Fragment.CODEC, MenuSyncNetworking::fragment);
        registrar.playToClient(MenuActionPayloads.Result.TYPE, MenuActionPayloads.Result.CODEC, (payload, context) -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientMenuSync.receiveAction(payload);
        });
    }

    private static void control(SyncPayloads.Control message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        long tick = player.server.getTickCount();
        var session = sessions.get(player.getUUID());
        if (!matches(session, message)) return;
        if (message.kind() == SyncPayloads.CLOSE) {
            session.input.clear();
            session.binding.close();
            sessions.remove(player.getUUID());
            return;
        }
        if (player.containerMenu != session.binding.model) return;
        if (message.kind() == SyncPayloads.READY && !session.start.ready) {
            session.start.ready = true;
            pump(session, tick);
            return;
        }
        if (message.kind() == SyncPayloads.ACK) {
            try {
                session.binding.acknowledge(message.revision(), message.batch(), tick);
            } catch (RuntimeException failure) {
                fail(session, failure);
            }
        }
    }
    /** Open the session; body transmission waits for client attachment and policy validation. */
    public static void opened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getContainer() instanceof SyncedMenu synced))
            return;
        if (!player.connection.hasChannel(SyncPayloads.Bootstrap.TYPE)
                || !player.connection.hasChannel(SyncPayloads.Control.TYPE)
                || !player.connection.hasChannel(SyncPayloads.Data.TYPE)) return;
        var binding = synced.menuSync();
        if (binding.model != event.getContainer()) return;
        var old = sessions.remove(player.getUUID());
        if (old != null) {
            old.input.clear();
            if (old.binding != binding) old.binding.close();
        }
        long tick = player.server.getTickCount();
        var bucket = bandwidth
                .computeIfAbsent(
                        player.connection.getConnection(),
                        ignored ->
                                new ConnectionBudget(player.server, new TokenBucket(binding.limits.bandwidth(), tick)))
                .bucket();
        bucket.configure(binding.limits.bandwidth(), tick);
        binding.serverStart(bucket);
        UUID nonce = UUID.randomUUID(), token = UUID.randomUUID();
        var session = new Session(
                player,
                binding,
                nonce,
                1,
                token,
                new RequestWindow(token, binding.options.actions().actionsPerTick()),
                new ActionInput(),
                new StartGate(tick));
        sessions.put(player.getUUID(), session);
        PacketDistributor.sendToPlayer(
                player,
                new SyncPayloads.Bootstrap(
                        binding.model.containerId,
                        nonce,
                        1,
                        token,
                        binding.schemaId(),
                        binding.fingerprint(),
                        binding.limits));
    }

    private static void action(MenuActionPayloads.Request message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var session = actionSession(player, message.menu(), message.nonce(), message.request(), message.session());
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
        if (message.data().length > session.binding.options.actions().fragmentBytes()) {
            reject(
                    session,
                    message.sequence(),
                    message.action(),
                    ActionFailure.BODY_BYTES,
                    message.data().length,
                    session.binding.options.actions().fragmentBytes(),
                    "Inline action exceeds configured fragment size");
            return;
        }
        reply(session, message.sequence(), apply(session, message.action(), message.data()));
    }

    private static boolean admit(Session session, long sequence, String action) {
        var admission = session.actions.admit(session.token, sequence, session.player.server.getTickCount());
        if (admission == RequestWindow.Admission.STALE) return false;
        if (admission == RequestWindow.Admission.THROTTLED) {
            reject(
                    session,
                    sequence,
                    action,
                    ActionFailure.THROTTLED,
                    -1,
                    session.binding.options.actions().actionsPerTick(),
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

    private static Session actionSession(ServerPlayer player, int menu, UUID nonce, long request, UUID token) {
        var session = sessions.get(player.getUUID());
        if (session == null
                || session.binding.model != player.containerMenu
                || menu != player.containerMenu.containerId
                || !session.nonce.equals(nonce)
                || session.request != request
                || !session.token.equals(token)
                || session.binding.status() == MenuSync.Status.CLOSED
                || session.binding.status() == MenuSync.Status.FAILED) return null;
        return session;
    }

    private static boolean mayAct(Session session) {
        var player = session.player;
        return player.isAlive()
                && !player.isSpectator()
                && player.containerMenu.stillValid(player)
                && session.binding.statistics().revision() >= 1;
    }

    private static MenuSync.ActionResult apply(Session session, String action, byte[] data) {
        try {
            return session.binding.applyAction(action, data, session.player);
        } catch (RuntimeException failure) {
            LogUtils.getLogger()
                    .warn(
                            "Menu action handler failed: schema={}, action={}",
                            session.binding.schemaId(),
                            action,
                            failure);
            return new MenuSync.ActionResult(
                    0,
                    action,
                    MenuSync.ActionStatus.REJECTED,
                    ActionFailure.REJECTED,
                    -1,
                    -1,
                    "Consumer handler failed");
        }
    }

    private static void reply(Session session, long sequence, MenuSync.ActionResult result) {
        var completed = new MenuSync.ActionResult(
                sequence,
                result.action(),
                result.status(),
                result.failure(),
                result.actual(),
                result.limit(),
                result.detail());
        session.binding.warnAction(completed, session.player.server.getTickCount());
        PacketDistributor.sendToPlayer(
                session.player,
                new MenuActionPayloads.Result(
                        session.binding.model.containerId,
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

    private static void reject(
            Session session,
            long sequence,
            String action,
            ActionFailure reason,
            long actual,
            long limit,
            String detail) {
        var status = reason == ActionFailure.THROTTLED
                ? MenuSync.ActionStatus.THROTTLED
                : reason == ActionFailure.ASSEMBLY_TIMEOUT
                        ? MenuSync.ActionStatus.EXPIRED
                        : reason == ActionFailure.REJECTED
                                ? MenuSync.ActionStatus.REJECTED
                                : MenuSync.ActionStatus.INVALID;
        reply(session, sequence, new MenuSync.ActionResult(sequence, action, status, reason, actual, limit, detail));
    }

    private static void fragment(MenuActionPayloads.Fragment message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var session = actionSession(player, message.menu(), message.nonce(), message.request(), message.session());
        if (session == null) return;
        long tick = player.server.getTickCount();
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
            input.assembly = new MessageAssembly(message.total(), limit, tick);
            input.sequence = message.sequence();
            input.action = message.action();
            input.total = message.total();
        }
        try {
            if (!input.action.equals(message.action())
                    || input.total != message.total()
                    || message.data().length > session.binding.options.actions().fragmentBytes())
                throw new SyncException("Invalid action fragment metadata/size");
            byte[] data = input.assembly.append(message.offset(), message.data(), tick);
            if (data != null) {
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
            }
        } catch (RuntimeException invalid) {
            input.clear();
            reject(
                    session,
                    message.sequence(),
                    message.action(),
                    ActionFailure.MALFORMED,
                    -1,
                    -1,
                    MenuSync.concise(invalid.getMessage()));
        }
    }

    private static boolean matches(Session session, SyncPayloads.Control message) {
        return session != null
                && session.binding.model.containerId == message.menu()
                && session.nonce.equals(message.nonce())
                && session.request == message.request()
                && session.token.equals(message.session());
    }

    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            var session = iterator.next();
            if (session.player.server != server) continue;
            if (server.getPlayerList().getPlayer(session.player.getUUID()) != session.player
                    || session.player.containerMenu != session.binding.model) {
                session.input.clear();
                session.binding.close();
                iterator.remove();
                continue;
            }
            if (session.input.assembly != null
                    && session.input.assembly.expired(
                            server.getTickCount(),
                            session.binding.options.actions().progressTimeoutTicks())) {
                reject(
                        session,
                        session.input.sequence,
                        session.input.action,
                        ActionFailure.ASSEMBLY_TIMEOUT,
                        -1,
                        session.binding.options.actions().progressTimeoutTicks(),
                        "Action assembly made no progress");
                session.input.clear();
            }
            pump(session, server.getTickCount());
        }
    }

    private static void pump(Session session, long tick) {
        try {
            if (session.binding.status() == MenuSync.Status.FAILED
                    || session.binding.status() == MenuSync.Status.CLOSED) return;
            if (!session.start.ready) {
                if (tick - session.start.openedAt > session.binding.limits.timeoutTicks())
                    throw new SyncException("Client did not confirm menu transport policy");
                return;
            }
            session.binding.serverPump(
                    session.player.registryAccess(),
                    tick,
                    () -> session.player.connection.getConnection().channel().isWritable(),
                    batch -> PacketDistributor.sendToPlayer(
                            session.player,
                            new SyncPayloads.Data(
                                    session.binding.model.containerId,
                                    session.nonce,
                                    session.request,
                                    session.token,
                                    batch,
                                    "")));
        } catch (RuntimeException failure) {
            fail(session, failure);
        }
    }

    private static void fail(Session session, RuntimeException failure) {
        session.input.clear();
        session.binding.fail(failure.getMessage() == null ? "Sync failure" : failure.getMessage());
        LogUtils.getLogger()
                .warn("Menu synchronization failed for {}: {}", session.binding.schemaId(), failure.toString());
        PacketDistributor.sendToPlayer(
                session.player,
                new SyncPayloads.Data(
                        session.binding.model.containerId,
                        session.nonce,
                        session.request,
                        session.token,
                        null,
                        "Menu synchronization failed; reopen the menu"));
    }

    public static void stopped(ServerStoppedEvent event) {
        sessions.values().removeIf(s -> {
            if (s.player.server != event.getServer()) return false;
            s.input.clear();
            s.binding.close();
            return true;
        });
        bandwidth.values().removeIf(account -> account.server() == event.getServer());
    }

    private MenuSyncNetworking() {}
}
