package dev.composemc.forge.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.composemc.sync.state.SyncCodecs;
import dev.composemc.sync.state.SyncLimits;
import dev.composemc.sync.state.SyncSchema;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;

class ServerPushInitialSnapshotTest {
    @Test
    void serverCanSendTheInitialSnapshotInTheSameTickItStartsTheSession() {
        var serverValue = new AtomicReference<>("already available");
        var clientValue = new AtomicReference<>("");
        var schema = SyncSchema.<AbstractContainerMenu>builder("test:server-push", 1)
                .field(
                        "value",
                        SyncCodecs.string(128),
                        menu -> serverValue.get(),
                        (menu, value) -> clientValue.set(value))
                .build();
        var limits =
                new SyncLimits(1024, dev.composemc.sync.transport.TransferBudget.steady(1024), 2, 2048, 4096, 16, 100);
        try (var server = MenuSync.bind(null, schema, dev.composemc.sync.MenuSyncOptions.DEFAULT.withState(limits));
                var client =
                        MenuSync.bind(null, schema, dev.composemc.sync.MenuSyncOptions.DEFAULT.withState(limits))) {
            server.serverStart(new dev.composemc.sync.transport.TokenBucket(limits.bandwidth(), 42));
            client.clientStart();
            server.serverPump(42, () -> true, batch -> {
                client.receive(batch, 42);
                server.acknowledge(batch.revision(), batch.index(), 42);
            });
            assertEquals("already available", clientValue.get());
            assertTrue(client.hasSnapshot());
            assertEquals(1, client.statistics().revision());
        }
    }
}
