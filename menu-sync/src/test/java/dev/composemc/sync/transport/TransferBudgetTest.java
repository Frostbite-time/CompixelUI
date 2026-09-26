package dev.composemc.sync.transport;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import dev.composemc.sync.state.SyncBatch;
import dev.composemc.sync.state.SyncCodecs;
import dev.composemc.sync.state.SyncLimits;
import dev.composemc.sync.state.SyncPublisher;
import dev.composemc.sync.state.SyncSchema;

class TransferBudgetTest {
    @Test void burstDrainsToSustainedRateAndIdleCreditCapsWithoutOverflow() {
        var bucket = new TokenBucket(new TransferBudget(10, 100, 40), 0);
        assertEquals(40, bucket.available(0)); bucket.consume(40, 0);
        assertEquals(0, bucket.available(0));
        bucket.consume(40, 1); bucket.consume(40, 2);
        assertEquals(0, bucket.credit(2));
        assertEquals(10, bucket.available(3)); bucket.consume(10, 3);
        assertEquals(100, bucket.credit(Long.MAX_VALUE));
    }
    @Test void menuChangesPreserveCreditAndTheSameTickCeiling() {
        var bucket = new TokenBucket(new TransferBudget(10, 100, 40), 0);
        bucket.consume(40, 0);
        bucket.configure(new TransferBudget(20, 1000, 40), 0);
        assertEquals(60, bucket.credit(0)); assertEquals(0, bucket.available(0));
        assertEquals(80, bucket.credit(1));
        bucket.configure(new TransferBudget(1, 20, 10), 1);
        assertEquals(20, bucket.credit(1));
        assertThrows(IllegalArgumentException.class, () -> bucket.available(0));
    }
    @Test void stateUsesPartialBatchCreditAndChecksBackpressureBetweenPackets() {
        class Model { String text = "x".repeat(200); }
        var schema = SyncSchema.<Model>builder("test:burst", 1)
                .field("text", SyncCodecs.string(1024), m -> m.text, (m, v) -> m.text = v).build();
        var limits = new SyncLimits(128, new TransferBudget(16, 256, 64), 16, 1024, 4096, 100, 200);
        var packets = new ArrayList<SyncBatch>();
        try (var publisher = new SyncPublisher<>(schema, limits)) {
            publisher.pump(new Model(), 0, () -> packets.isEmpty(), packets::add);
            assertEquals(1, packets.size()); assertEquals(64, packets.get(0).dataSize());
            publisher.pump(new Model(), 1, () -> false, packets::add);
            assertEquals(1, packets.size());
            publisher.pump(new Model(), 2, packets::add);
            assertEquals(2, packets.size()); assertEquals(64, packets.get(1).dataSize());
        }
    }
    @Test void newPublisherOnSameConnectionDoesNotReceiveAnotherBurst() {
        class Model { int value = 7; }
        var schema = SyncSchema.<Model>builder("test:reopen", 1)
                .field("v", SyncCodecs.INT, m -> m.value, (m, v) -> m.value = v).build();
        var limits = new SyncLimits(128, new TransferBudget(1, 10, 10), 16, 64, 1024, 100, 200);
        var account = new TokenBucket(limits.bandwidth(), 0);
        var packets = new ArrayList<SyncBatch>();
        try (var first = new SyncPublisher<>(schema, limits, account)) { first.pump(new Model(), 0, packets::add); }
        assertEquals(10, packets.get(0).dataSize());
        try (var second = new SyncPublisher<>(schema, limits, account)) {
            second.pump(new Model(), 0, packets::add); assertEquals(1, packets.size());
            second.pump(new Model(), 1, packets::add); assertEquals(1, packets.get(1).dataSize());
        }
    }
    @Test void largeConsumerBudgetsAreAcceptedWithoutOldArbitraryCeilings() {
        var limits = new SyncLimits(128 * 1024, new TransferBudget(1024, 8L << 30, 1024 * 1024),
                1000, 512 * 1024 * 1024, 8L << 30, 2_000_000, 1200);
        assertEquals(8L << 30, limits.maxUpdateBytes());
        assertEquals(2_000_000, limits.maxEntries());
    }
}
