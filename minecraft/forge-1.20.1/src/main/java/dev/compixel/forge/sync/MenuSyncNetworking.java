package dev.compixel.forge.sync;

import dev.compixel.sync.session.CodecScope;
import dev.compixel.sync.session.ServerSyncSessions;
import dev.compixel.sync.session.SyncLog;
import dev.compixel.sync.session.SyncMessage;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Registers menu synchronization's channel and drives the server's sessions from loader events. */
public final class MenuSyncNetworking {
    private static final ServerSyncSessions<ServerPlayer> SESSIONS = new ServerSyncSessions<>(new ServerHost());

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("compixel", "menu_sync"),
            () -> "5",
            MenuSyncNetworking::acceptVersion,
            MenuSyncNetworking::acceptVersion);

    private static boolean acceptVersion(String version) {
        return version.equals("5")
                || version.equals(NetworkRegistry.ABSENT.version())
                || version.equals(NetworkRegistry.ACCEPTVANILLA);
    }

    public static void register() {
        packet(SyncPayloads.Control.class, 0, NetworkDirection.PLAY_TO_SERVER, SyncPayloads.Control.CODEC, (p, c) -> {
            if (c.getSender() != null) SESSIONS.receive(c.getSender(), p.message());
        });
        packet(
                SyncPayloads.Bootstrap.class,
                1,
                NetworkDirection.PLAY_TO_CLIENT,
                SyncPayloads.Bootstrap.CODEC,
                (p, c) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientMenuSync.receive(p.message());
                });
        packet(SyncPayloads.Data.class, 2, NetworkDirection.PLAY_TO_CLIENT, SyncPayloads.Data.CODEC, (p, c) -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientMenuSync.receive(p.message());
        });
        packet(
                SyncPayloads.ActionRequest.class,
                3,
                NetworkDirection.PLAY_TO_SERVER,
                SyncPayloads.ActionRequest.CODEC,
                (p, c) -> {
                    if (c.getSender() != null) SESSIONS.receive(c.getSender(), p.message());
                });
        packet(
                SyncPayloads.ActionFragment.class,
                4,
                NetworkDirection.PLAY_TO_SERVER,
                SyncPayloads.ActionFragment.CODEC,
                (p, c) -> {
                    if (c.getSender() != null) SESSIONS.receive(c.getSender(), p.message());
                });
        packet(
                SyncPayloads.ActionReply.class,
                5,
                NetworkDirection.PLAY_TO_CLIENT,
                SyncPayloads.ActionReply.CODEC,
                (p, c) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientMenuSync.receive(p.message());
                });
    }

    private static <T> void packet(
            Class<T> type,
            int id,
            NetworkDirection direction,
            PacketCodec<T> codec,
            BiConsumer<T, NetworkEvent.Context> handler) {
        CHANNEL.messageBuilder(type, id, direction)
                .encoder((p, b) -> codec.encode(b, p))
                .decoder(codec::decode)
                .consumerMainThread((p, c) -> handler.accept(p, c.get()))
                .add();
    }

    static boolean hasChannel(Connection connection) {
        return CHANNEL.isRemotePresent(connection);
    }

    static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    /** Starts the session of a synchronized menu; its state follows once the client confirms the policy. */
    public static void opened(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getContainer() instanceof SyncedMenu synced)
            SESSIONS.opened(player, event.getContainer(), synced.menuSync());
    }

    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) SESSIONS.tick(event.getServer());
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
            return player.server;
        }

        @Override
        public long tick(ServerPlayer player) {
            return player.server.getTickCount();
        }

        @Override
        public boolean current(ServerPlayer player) {
            return player.server.getPlayerList().getPlayer(player.getUUID()) == player;
        }

        @Override
        public Object connection(ServerPlayer player) {
            return player.connection.connection;
        }

        @Override
        public boolean channelsAvailable(ServerPlayer player) {
            return hasChannel(player.connection.connection);
        }

        @Override
        public boolean writable(ServerPlayer player) {
            return player.connection.connection.channel().isWritable();
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
            return CodecScope.NONE;
        }

        @Override
        public void send(ServerPlayer player, SyncMessage message) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), SyncPayloads.of(message));
        }

        @Override
        public SyncLog log() {
            return MenuSync.LOG;
        }
    }

    private MenuSyncNetworking() {}
}
