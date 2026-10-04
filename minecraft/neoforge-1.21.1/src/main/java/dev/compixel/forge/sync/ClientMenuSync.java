package dev.compixel.forge.sync;

import dev.compixel.sync.action.ActionSubmission;
import dev.compixel.sync.session.ClientSyncSession;
import dev.compixel.sync.session.CodecScope;
import dev.compixel.sync.session.SyncAction;
import dev.compixel.sync.session.SyncBinding;
import dev.compixel.sync.session.SyncLog;
import dev.compixel.sync.session.SyncMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client adapter only. Common registration does not initialize this class on a server. */
public final class ClientMenuSync {
    private static final ClientSyncSession SESSION = new ClientSyncSession(new Host());

    public static void tick(ClientTickEvent.Post event) {
        SESSION.tick();
    }

    static void receive(SyncMessage.Bootstrap message) {
        SESSION.receive(message);
    }

    static void receive(SyncMessage.Data message) {
        SESSION.receive(message);
    }

    static void receive(SyncMessage.ActionReply message) {
        SESSION.receive(message);
    }

    static <M extends AbstractContainerMenu, V> ActionSubmission request(
            MenuSync<M> binding, SyncAction<? super M, ServerPlayer, V> action, V value) {
        return SESSION.request(binding, action, value);
    }

    static boolean sending(MenuSync<?> binding) {
        return SESSION.sending(binding);
    }

    private static final class Host implements ClientSyncSession.Host {
        @Override
        public Object connection() {
            return Minecraft.getInstance().getConnection();
        }

        @Override
        public Object openMenu() {
            var player = Minecraft.getInstance().player;
            return player == null ? null : player.containerMenu;
        }

        @Override
        public SyncBinding<?, ?, ?> openBinding() {
            return openMenu() instanceof SyncedMenu synced ? synced.menuSync() : null;
        }

        @Override
        public boolean channelsAvailable() {
            var connection = Minecraft.getInstance().getConnection();
            return connection != null
                    && connection.hasChannel(SyncPayloads.Bootstrap.TYPE)
                    && connection.hasChannel(SyncPayloads.Control.TYPE)
                    && connection.hasChannel(SyncPayloads.Data.TYPE)
                    && connection.hasChannel(SyncPayloads.ActionRequest.TYPE)
                    && connection.hasChannel(SyncPayloads.ActionFragment.TYPE)
                    && connection.hasChannel(SyncPayloads.ActionReply.TYPE);
        }

        @Override
        public boolean controlAvailable() {
            var connection = Minecraft.getInstance().getConnection();
            return connection != null && connection.hasChannel(SyncPayloads.Control.TYPE);
        }

        @Override
        public boolean writable() {
            return Minecraft.getInstance()
                    .getConnection()
                    .getConnection()
                    .channel()
                    .isWritable();
        }

        @Override
        public boolean onGameThread() {
            return Minecraft.getInstance().isSameThread();
        }

        /** The client's registries, which native codecs use for menu values and actions. */
        @Override
        public CodecScope codecs() {
            var player = Minecraft.getInstance().player;
            if (player == null) throw new IllegalStateException("Menu synchronization needs a client player");
            return SyncRegistries.scope(player.registryAccess());
        }

        @Override
        public void send(SyncMessage message) {
            PacketDistributor.sendToServer(SyncPayloads.of(message));
        }

        @Override
        public SyncLog log() {
            return MenuSync.LOG;
        }
    }

    private ClientMenuSync() {}
}
