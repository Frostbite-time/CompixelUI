package dev.compixel.forge.sync;

import dev.compixel.sync.session.CodecScope;
import dev.compixel.sync.session.ServerSyncSessions;
import dev.compixel.sync.session.SyncLog;
import dev.compixel.sync.session.SyncMessage;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers menu synchronization's payloads and drives the server's sessions from loader events. */
public final class MenuSyncNetworking {
    private static final ServerSyncSessions<ServerPlayer> SESSIONS = new ServerSyncSessions<>(new ServerHost());

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("5").optional();
        registrar.playToServer(SyncPayloads.Control.TYPE, SyncPayloads.Control.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) SESSIONS.receive(player, payload.message());
        });
        registrar.playToClient(SyncPayloads.Bootstrap.TYPE, SyncPayloads.Bootstrap.CODEC, (payload, context) -> {
            if (FMLEnvironment.getDist() == Dist.CLIENT) ClientMenuSync.receive(payload.message());
        });
        registrar.playToClient(SyncPayloads.Data.TYPE, SyncPayloads.Data.CODEC, (payload, context) -> {
            if (FMLEnvironment.getDist() == Dist.CLIENT) ClientMenuSync.receive(payload.message());
        });
        registrar.playToServer(
                SyncPayloads.ActionRequest.TYPE, SyncPayloads.ActionRequest.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) SESSIONS.receive(player, payload.message());
                });
        registrar.playToServer(
                SyncPayloads.ActionFragment.TYPE, SyncPayloads.ActionFragment.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) SESSIONS.receive(player, payload.message());
                });
        registrar.playToClient(SyncPayloads.ActionReply.TYPE, SyncPayloads.ActionReply.CODEC, (payload, context) -> {
            if (FMLEnvironment.getDist() == Dist.CLIENT) ClientMenuSync.receive(payload.message());
        });
    }

    /** Starts the session of a synchronized menu; its state follows once the client confirms the policy. */
    public static void opened(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getContainer() instanceof SyncedMenu synced)
            SESSIONS.opened(player, event.getContainer(), synced.menuSync());
    }

    public static void tick(ServerTickEvent.Post event) {
        SESSIONS.tick(event.getServer());
    }

    public static void stopped(ServerStoppedEvent event) {
        SESSIONS.stopped(event.getServer());
    }

    private static final class ServerHost implements ServerSyncSessions.Host<ServerPlayer> {
        @Override
        public UUID id(ServerPlayer player) {
            return player.getUUID();
        }

        @Override
        public Object server(ServerPlayer player) {
            return player.level().getServer();
        }

        @Override
        public long tick(ServerPlayer player) {
            return player.level().getServer().getTickCount();
        }

        @Override
        public boolean current(ServerPlayer player) {
            return player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player;
        }

        @Override
        public Object connection(ServerPlayer player) {
            return player.connection.getConnection();
        }

        @Override
        public boolean channelsAvailable(ServerPlayer player) {
            return player.connection.hasChannel(SyncPayloads.Bootstrap.TYPE)
                    && player.connection.hasChannel(SyncPayloads.Control.TYPE)
                    && player.connection.hasChannel(SyncPayloads.Data.TYPE);
        }

        @Override
        public boolean writable(ServerPlayer player) {
            return player.connection.getConnection().channel().isWritable();
        }

        @Override
        public Object openMenu(ServerPlayer player) {
            return player.containerMenu;
        }

        @Override
        public boolean mayAct(ServerPlayer player) {
            return player.isAlive() && !player.isSpectator() && player.containerMenu.stillValid(player);
        }

        @Override
        public CodecScope codecs(ServerPlayer player) {
            return SyncRegistries.scope(player.registryAccess());
        }

        @Override
        public void send(ServerPlayer player, SyncMessage message) {
            PacketDistributor.sendToPlayer(player, SyncPayloads.of(message));
        }

        @Override
        public SyncLog log() {
            return MenuSync.LOG;
        }
    }

    private MenuSyncNetworking() {}
}
