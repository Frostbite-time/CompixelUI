package dev.compixel.forge.sync;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.action.ActionQueue;
import dev.compixel.sync.session.SyncStatus;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncPublisher;
import dev.compixel.sync.state.SyncSchema;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;

class MenuSyncConfigurationTest {
    private static final SyncSchema<AbstractContainerMenu> SCHEMA = SyncSchema.<AbstractContainerMenu>builder(
                    "test:binding", 1)
            .field("v", SyncCodecs.INT, m -> 1, (m, v) -> {})
            .build();

    @Test
    void stateBatchesAndActionFragmentsFitMinecraftPayloads() {
        assertTrue(SyncPublisher.BATCH_BYTES <= MenuTransportLimits.MAX_STATE_BATCH_BYTES);
        assertTrue(ActionQueue.FRAGMENT_BYTES <= MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES);
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
