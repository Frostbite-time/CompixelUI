package dev.compixel.sync.action;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionQueueTest {
    @Test
    void fragmentedRequestsKeepFifoOrder() {
        var queue = new ActionQueue();
        queue.offer("large", new byte[3 * ActionQueue.FRAGMENT_BYTES]);
        queue.offer("small", new byte[] {7});
        var wire = new ArrayList<ActionQueue.Part>();
        queue.pump(0, wire::add);
        assertEquals(4, wire.size());
        assertEquals(3 * ActionQueue.FRAGMENT_BYTES, wire.get(0).total());
        assertEquals(ActionQueue.FRAGMENT_BYTES, wire.get(1).offset());
        assertFalse(wire.get(0).single());
        assertEquals("small", wire.get(3).action());
        assertTrue(wire.get(3).single());
        assertFalse(queue.sending());
        assertEquals(2, queue.pendingActions());
        assertEquals("large", queue.reply(1));
        assertEquals("small", queue.reply(2));
        assertEquals(0, queue.inFlightBytes());
    }

    @Test
    void unconfirmedBytesStayWithinTheWindow() {
        var queue = new ActionQueue();
        int size = 3 * ActionQueue.WINDOW_BYTES;
        queue.offer("large", new byte[size]);
        long[] sent = {0};
        queue.pump(0, part -> sent[0] += part.data().length);
        assertEquals(ActionQueue.WINDOW_BYTES, sent[0]);
        assertEquals(ActionQueue.WINDOW_BYTES, queue.inFlightBytes());
        // Nothing more until the server confirms what arrived.
        queue.pump(1, part -> sent[0] += part.data().length);
        assertEquals(ActionQueue.WINDOW_BYTES, sent[0]);
        queue.progress(1, ActionQueue.FRAGMENT_BYTES * 2);
        queue.pump(2, part -> sent[0] += part.data().length);
        assertEquals(ActionQueue.WINDOW_BYTES + 2L * ActionQueue.FRAGMENT_BYTES, sent[0]);
        // A lower confirmation changes nothing; one beyond what was sent confirms only what was sent.
        queue.progress(1, 1);
        assertEquals(ActionQueue.WINDOW_BYTES, queue.inFlightBytes());
        queue.progress(1, size);
        assertEquals(0, queue.inFlightBytes());
        for (int tick = 3; queue.sending(); tick++) {
            queue.progress(1, (int) sent[0]);
            queue.pump(tick, part -> sent[0] += part.data().length);
            assertTrue(tick < 100);
        }
        assertEquals(size, sent[0]);
        assertEquals("large", queue.reply(1));
        assertEquals(0, queue.inFlightBytes());
    }

    @Test
    void aReplyRetiresTheUnsentTailOfARefusedAction() {
        var queue = new ActionQueue();
        queue.offer("refused", new byte[2 * ActionQueue.WINDOW_BYTES]);
        queue.offer("next", new byte[] {1});
        var wire = new ArrayList<ActionQueue.Part>();
        queue.pump(0, wire::add);
        assertTrue(queue.sending());
        assertEquals("refused", queue.reply(1));
        assertEquals(0, queue.inFlightBytes());
        queue.pump(1, wire::add);
        assertEquals("next", wire.get(wire.size() - 1).action());
        assertFalse(queue.sending());
    }

    @Test
    void zeroBodyCommandsAndRepeatedPumpsRespectPerTickAdmission() {
        var queue = new ActionQueue();
        for (int i = 0; i < ActionQueue.ACTIONS_PER_TICK + 4; i++)
            assertTrue(queue.offer("empty", new byte[0]).queued());
        var wire = new ArrayList<ActionQueue.Part>();
        queue.pump(0, wire::add);
        queue.pump(0, wire::add);
        assertEquals(ActionQueue.ACTIONS_PER_TICK, wire.size());
        queue.pump(1, wire::add);
        assertEquals(ActionQueue.ACTIONS_PER_TICK + 4, wire.size());
        assertTrue(wire.stream().allMatch(ActionQueue.Part::single));
        assertEquals(
                List.of(0), wire.stream().map(p -> p.data().length).distinct().toList());
    }
}
