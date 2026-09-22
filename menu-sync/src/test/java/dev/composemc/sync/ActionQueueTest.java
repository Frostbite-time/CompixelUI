package dev.composemc.sync;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ActionQueueTest {
    private static ActionLimits limits(long bytes, int pending, int admissions) {
        return new ActionLimits(16, new TransferBudget(8, 64, 32), bytes, pending, admissions, 10, 3, 5);
    }
    @Test void byteAndCountRejectionNeverConsumeSequenceOrDiscardExistingRequests() {
        var limits = limits(64, 2, 8);
        var queue = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        assertEquals(1, queue.offer("first", new byte[40], 0).sequence());
        var refused = queue.offer("large", new byte[25], 0);
        assertEquals(ActionFailure.QUEUE_BYTES, refused.failure()); assertEquals(65, refused.actual()); assertEquals(64, refused.limit());
        assertEquals(2, queue.offer("second", new byte[24], 0).sequence());
        assertEquals(ActionFailure.PENDING_ACTIONS, queue.offer("third", new byte[0], 0).failure());
        assertEquals(64, queue.queuedBytes());
        assertEquals("first", queue.reply(1));
        assertEquals(24, queue.queuedBytes());
        assertEquals(3, queue.offer("third", new byte[1], 0).sequence());
    }
    @Test void fragmentedRequestsKeepFifoAndWaitForWritableTransport() {
        var limits = limits(1024, 8, 8);
        var queue = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        queue.offer("large", new byte[48], 0); queue.offer("small", new byte[]{7}, 0);
        var wire = new ArrayList<ActionQueue.Part>();
        queue.pump(0, () -> false, wire::add); assertTrue(wire.isEmpty());
        queue.pump(0, () -> true, wire::add);
        assertEquals(2, wire.size()); assertEquals(48, wire.get(0).total()); assertEquals(16, wire.get(1).offset());
        queue.pump(1, () -> true, wire::add);
        assertEquals(4, wire.size()); assertEquals("large", wire.get(2).action()); assertEquals("small", wire.get(3).action());
        assertTrue(wire.get(3).single()); assertFalse(queue.sending()); assertEquals(0, queue.queuedBytes());
        assertEquals(2, queue.pendingActions());
    }
    @Test void smallRequestsUseAvailableCreditWithoutWaitingForAFullFragment() {
        var limits = new ActionLimits(128, TransferBudget.steady(3), 100, 10, 10, 100, 10, 10);
        var queue = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        queue.offer("small", new byte[8], 0);
        var wire = new ArrayList<ActionQueue.Part>();
        for (int tick = 0; tick < 3; tick++) queue.pump(tick, () -> true, wire::add);
        assertEquals(java.util.List.of(3, 3, 2), wire.stream().map(p -> p.data().length).toList());
        assertTrue(wire.stream().noneMatch(ActionQueue.Part::single));
    }
    @Test void activeProgressDoesNotExpireAgainstTheOriginalEnqueueTime() {
        var limits = new ActionLimits(8, TransferBudget.steady(1), 100, 10, 10, 2, 2, 3);
        var queue = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        queue.offer("slow", new byte[10], 0);
        for (int tick = 0; tick < 10; tick++) {
            queue.pump(tick, () -> true, part -> {}); assertNull(queue.expired(tick));
        }
        assertNull(queue.expired(11));
        assertEquals(ActionFailure.REPLY_TIMEOUT, queue.expired(12).failure());
    }
    @Test void distinguishesWaitingAndStalledPartialTransfer() {
        var limits = limits(100, 10, 10);
        var waiting = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        waiting.offer("waiting", new byte[60], 0);
        assertEquals(ActionFailure.QUEUE_TIMEOUT, waiting.expired(10).failure());
        waiting.pump(0, () -> true, part -> {});
        assertEquals(ActionFailure.SEND_TIMEOUT, waiting.expired(3).failure());
    }
    @Test void zeroBodyCommandsAndRepeatedPumpsRespectPerTickAdmission() {
        var limits = limits(1, 10, 2);
        var queue = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        for (int i = 0; i < 5; i++) assertTrue(queue.offer("empty", new byte[0], 0).queued());
        var wire = new ArrayList<ActionQueue.Part>();
        queue.pump(0, () -> true, wire::add); queue.pump(0, () -> true, wire::add);
        assertEquals(2, wire.size());
        queue.pump(1, () -> true, wire::add); assertEquals(4, wire.size());
    }
    @Test void configurableQueueCanTransmitBodiesAboveTwoMiB() {
        int size = 3 * 1024 * 1024;
        var limits = new ActionLimits(16 * 1024, new TransferBudget(32 * 1024, size, 1024 * 1024),
                8L * 1024 * 1024, 100, 40, 400, 400, 400);
        var queue = new ActionQueue(limits, new TokenBucket(limits.bandwidth(), 0));
        assertTrue(queue.offer("large", new byte[size], 0).queued());
        long[] bytes = {0};
        for (int tick = 0; tick < 3; tick++) queue.pump(tick, () -> true, part -> bytes[0] += part.data().length);
        assertEquals(size, bytes[0]); assertFalse(queue.sending());
    }
}
