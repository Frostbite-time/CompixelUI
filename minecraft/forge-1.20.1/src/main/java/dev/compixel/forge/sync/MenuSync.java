package dev.compixel.forge.sync;

import com.mojang.logging.LogUtils;
import dev.compixel.sync.MenuSyncOptions;
import dev.compixel.sync.action.ActionSubmission;
import dev.compixel.sync.session.SyncAction;
import dev.compixel.sync.session.SyncBinding;
import dev.compixel.sync.session.SyncLog;
import dev.compixel.sync.state.SyncSchema;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Synchronizes one native menu; see {@link SyncBinding}. Bind it where the menu is created, on both sides, register its
 * actions before the menu opens, and return it from {@link SyncedMenu#menuSync()}.
 */
public final class MenuSync<M extends AbstractContainerMenu> extends SyncBinding<M, ServerPlayer, MenuSync<M>> {
    static final SyncLog LOG = (message, error) -> LogUtils.getLogger().warn(message, error);

    private MenuSync(M menu, SyncSchema<M> schema, MenuSyncOptions options) {
        super(menu, schema, options, LOG);
        if (options.state().batchBytes() > MenuTransportLimits.MAX_STATE_BATCH_BYTES)
            throw new IllegalArgumentException("S2C batch exceeds Minecraft transport envelope: "
                    + options.state().batchBytes());
        if (options.actions().fragmentBytes() > MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES)
            throw new IllegalArgumentException("C2S fragment exceeds Minecraft transport envelope: "
                    + options.actions().fragmentBytes());
    }

    public static <M extends AbstractContainerMenu> MenuSync<M> bind(M menu, SyncSchema<M> schema) {
        return bind(menu, schema, MenuSyncOptions.DEFAULT);
    }

    public static <M extends AbstractContainerMenu> MenuSync<M> bind(
            M menu, SyncSchema<M> schema, MenuSyncOptions options) {
        return new MenuSync<>(menu, schema, options);
    }

    /**
     * Queues an action for the server, on the client game thread. {@code queued()} means accepted into the local
     * queue, never applied by the server; the outcome reaches {@link #onActionResult} listeners.
     */
    public <V> ActionSubmission request(SyncAction<? super M, ServerPlayer, V> action, V value) {
        if (FMLEnvironment.dist != Dist.CLIENT)
            throw new IllegalStateException("Menu actions must be submitted on the client");
        return ClientMenuSync.request(this, action, value);
    }

    /** Whether actions of this menu are still on their way to the server. Always false on a server. */
    public boolean isSendingAction() {
        return FMLEnvironment.dist == Dist.CLIENT && ClientMenuSync.sending(this);
    }

    @Override
    protected int menuId() {
        return menu().containerId;
    }
}
