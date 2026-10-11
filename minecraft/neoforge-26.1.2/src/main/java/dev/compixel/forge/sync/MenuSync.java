package dev.compixel.forge.sync;

import com.mojang.logging.LogUtils;
import dev.compixel.sync.action.ActionSubmission;
import dev.compixel.sync.session.SyncAction;
import dev.compixel.sync.session.SyncBinding;
import dev.compixel.sync.session.SyncLog;
import dev.compixel.sync.state.SyncSchema;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Synchronizes one native menu; see {@link SyncBinding}. Bind it where the menu is created, on both sides, register its
 * actions before the menu opens, and return it from {@link SyncedMenu#menuSync()}.
 */
public final class MenuSync<M extends AbstractContainerMenu> extends SyncBinding<M, ServerPlayer, MenuSync<M>> {
    static final SyncLog LOG = (message, error) -> LogUtils.getLogger().warn(message, error);

    private MenuSync(M menu, SyncSchema<M> schema) {
        super(menu, schema, LOG);
    }

    public static <M extends AbstractContainerMenu> MenuSync<M> bind(M menu, SyncSchema<M> schema) {
        return new MenuSync<>(menu, schema);
    }

    /**
     * Queues an action for the server, on the client game thread. {@code queued()} means accepted into the local
     * queue, never applied by the server; the outcome reaches {@link #onActionResult} listeners.
     */
    public <V> ActionSubmission request(SyncAction<? super M, ServerPlayer, V> action, V value) {
        if (FMLEnvironment.getDist() != Dist.CLIENT)
            throw new IllegalStateException("Menu actions must be submitted on the client");
        return ClientMenuSync.request(this, action, value);
    }

    /** Whether actions of this menu are still on their way to the server. Always false on a server. */
    public boolean isSendingAction() {
        return FMLEnvironment.getDist() == Dist.CLIENT && ClientMenuSync.sending(this);
    }

    @Override
    protected int menuId() {
        return menu().containerId;
    }
}
