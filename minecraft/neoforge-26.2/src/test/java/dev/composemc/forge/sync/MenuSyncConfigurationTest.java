package dev.composemc.forge.sync;

import dev.composemc.sync.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.composemc.sync.action.ActionLimits;
import dev.composemc.sync.state.SyncCodecs;
import dev.composemc.sync.state.SyncLimits;
import dev.composemc.sync.state.SyncSchema;
import dev.composemc.sync.transport.TransferBudget;

class MenuSyncConfigurationTest {
    private static final SyncSchema<AbstractContainerMenu> SCHEMA = SyncSchema.<AbstractContainerMenu>builder("test:options", 1)
            .field("v", SyncCodecs.INT, m -> 1, (m, v) -> {}).build();
    @Test void wirePolicyJoinsFingerprintButLocalLoggingDoesNot() {
        try (var standard = MenuSync.bind(null, SCHEMA);
             var quiet = MenuSync.bind(null, SCHEMA, MenuSyncOptions.DEFAULT.withRejectionLogInterval(-1))) {
            assertEquals(standard.fingerprint(), quiet.fingerprint());
            var actions = new ActionLimits(2048, TransferBudget.steady(256), 10_000, 100, 50, 900, 800, 700);
            try (var different = MenuSync.bind(null, SCHEMA, MenuSyncOptions.DEFAULT.withActions(actions))) {
                assertNotEquals(standard.fingerprint(), different.fingerprint());
            }
        }
    }
    @Test void moreThan32DeclaredActionsAreValidAndPhysicalEnvelopesAreChecked() {
        try (var binding = MenuSync.bind(null, SCHEMA)) {
            for (int i = 0; i < 65; i++) binding.action(MenuAction.of("action_" + i, SyncCodecs.INT, (m, p, v) -> true));
            assertNotNull(binding.fingerprint());
        }
        var oversized = new SyncLimits(MenuTransportLimits.MAX_STATE_BATCH_BYTES + 1, TransferBudget.steady(64), 1, 64, 1024, 100, 200);
        assertThrows(IllegalArgumentException.class, () -> MenuSync.bind(null, SCHEMA, MenuSyncOptions.DEFAULT.withState(oversized)));
    }
}
