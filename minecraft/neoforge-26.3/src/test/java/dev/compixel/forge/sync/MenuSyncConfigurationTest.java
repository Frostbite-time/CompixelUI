package dev.compixel.forge.sync;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.MenuSyncOptions;
import dev.compixel.sync.action.ActionLimits;
import dev.compixel.sync.session.SyncStatus;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncLimits;
import dev.compixel.sync.state.SyncSchema;
import dev.compixel.sync.transport.TransferBudget;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;

class MenuSyncConfigurationTest {
    private static final SyncSchema<AbstractContainerMenu> SCHEMA = SyncSchema.<AbstractContainerMenu>builder(
                    "test:options", 1)
            .field("v", SyncCodecs.INT, m -> 1, (m, v) -> {})
            .build();

    @Test
    void bindingsStayWithinMinecraftPayloadEnvelopes() {
        var batches = new SyncLimits(
                MenuTransportLimits.MAX_STATE_BATCH_BYTES + 1, TransferBudget.steady(64), 1, 64, 1024, 100, 200);
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuSync.bind(null, SCHEMA, MenuSyncOptions.DEFAULT.withState(batches)));
        var fragments = new ActionLimits(
                MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES + 1, TransferBudget.steady(64), 1024, 1, 1, 1, 1, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuSync.bind(null, SCHEMA, MenuSyncOptions.DEFAULT.withActions(fragments)));
    }

    @Test
    void menuActionsDeclareOnTheBinding() {
        try (var binding = MenuSync.bind(null, SCHEMA)) {
            String empty = binding.fingerprint();
            binding.action(MenuAction.of("mode", SyncCodecs.INT, (menu, player, value) -> value >= 0));
            assertNotEquals(empty, binding.fingerprint());
            assertEquals(SyncStatus.WAITING, binding.status());
            assertFalse(binding.hasSnapshot());
        }
    }
}
