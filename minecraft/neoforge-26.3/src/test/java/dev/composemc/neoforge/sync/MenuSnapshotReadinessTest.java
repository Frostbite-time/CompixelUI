package dev.composemc.neoforge.sync;

import dev.composemc.sync.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MenuSnapshotReadinessTest {
    private static SyncSchema<AbstractContainerMenu> schema(AtomicReference<String> text) {
        return SyncSchema.<AbstractContainerMenu>builder("test:readiness", 1)
                .field("text", SyncCodecs.string(1024), m -> text.get(), (m, v) -> text.set(v)).build();
    }
    @Test void committedStateStaysInteractiveWhileLaterBatchesAreIncomplete() {
        var server = new AtomicReference<>("initial"); var client = new AtomicReference<>("");
        var publications=new java.util.concurrent.atomic.AtomicInteger();
        var limits = new SyncLimits(64, dev.composemc.sync.TransferBudget.steady(64), 2, 2048, 4096, 16, 100);
        try (var publisher = new SyncPublisher<>(schema(server), limits); var binding = MenuSync.bind(null, schema(client), dev.composemc.sync.MenuSyncOptions.DEFAULT.withState(limits))) {
            binding.onUpdate(m->publications.incrementAndGet());
            binding.clientStart(); assertFalse(binding.hasSnapshot());
            publisher.pump(null, 0, batch -> { binding.receive(batch, 0); publisher.acknowledge(batch.revision(), batch.index(), 0); });
            assertTrue(binding.hasSnapshot()); assertEquals("initial", client.get());
            assertEquals(1,publications.get());
            server.set("x".repeat(300));
            publisher.pump(null, 1, batch -> { binding.receive(batch, 1); publisher.acknowledge(batch.revision(), batch.index(), 1); });
            assertEquals(MenuSync.Status.SYNCING, binding.status());
            assertTrue(binding.hasSnapshot()); assertEquals("initial", client.get());
            assertEquals(1,publications.get());
            for (int tick = 2; tick < 20 && publisher.busy(); tick++) {
                int now = tick;
                publisher.pump(null, now, batch -> { binding.receive(batch, now); publisher.acknowledge(batch.revision(), batch.index(), now); });
            }
            assertEquals(server.get(), client.get()); assertTrue(binding.hasSnapshot());
            assertEquals(2,publications.get());
            binding.fail("disconnected"); assertFalse(binding.hasSnapshot());
            binding.clientStart(); assertFalse(binding.hasSnapshot());
        }
    }
}
