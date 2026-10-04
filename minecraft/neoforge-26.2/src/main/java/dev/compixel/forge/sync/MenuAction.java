package dev.compixel.forge.sync;

import dev.compixel.sync.session.SyncAction;
import dev.compixel.sync.state.SyncCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** A small typed client intent for a native menu, handled on the server with the acting player; see {@link SyncAction}. */
public final class MenuAction<M extends AbstractContainerMenu, V> extends SyncAction<M, ServerPlayer, V> {
    @FunctionalInterface
    public interface Handler<M, V> extends SyncAction.Handler<M, ServerPlayer, V> {}

    private MenuAction(String id, SyncCodec<V> codec, int maximumBytes, Handler<M, V> handler) {
        super(id, codec, maximumBytes, handler);
    }

    public static <M extends AbstractContainerMenu, V> MenuAction<M, V> of(
            String id, SyncCodec<V> codec, Handler<M, V> handler) {
        return new MenuAction<>(id, codec, DEFAULT_MAX_BYTES, handler);
    }

    /** Explicitly permit larger intents. Transport fragments them below Minecraft payload limits. */
    public static <M extends AbstractContainerMenu, V> MenuAction<M, V> of(
            String id, SyncCodec<V> codec, int maximumBytes, Handler<M, V> handler) {
        return new MenuAction<>(id, codec, maximumBytes, handler);
    }
}
