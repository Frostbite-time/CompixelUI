package dev.compixel.sync.action;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owner-thread FIFO of encoded actions. It sends them in fragments while the bytes the server has not confirmed stay
 * within {@link #WINDOW_BYTES}; the server confirms a larger action's fragments as they arrive, and every action with
 * its reply.
 */
public final class ActionQueue {
    /** The largest part of an action that one message carries. */
    public static final int FRAGMENT_BYTES = 16 * 1024;
    /** Action bytes sent and not yet confirmed by the server, at most. */
    public static final int WINDOW_BYTES = 512 * 1024;
    /** New actions started per tick, at most; the server admits as many. */
    public static final int ACTIONS_PER_TICK = 16;

    public record Part(long sequence, String action, int total, int offset, byte[] data, boolean single) {}

    private static final class Pending {
        final long sequence;
        final String action;
        byte[] data;
        int offset, confirmed;
        boolean started;

        Pending(long sequence, String action, byte[] data) {
            this.sequence = sequence;
            this.action = action;
            this.data = data;
        }
    }

    // Actions waiting for their reply, and the ones among them not sent completely.
    private final LinkedHashMap<Long, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<Pending> outbound = new ArrayDeque<>();
    private long sequence, inFlightBytes, admissionTick = Long.MIN_VALUE;
    private int startedThisTick;

    /** Action bytes sent and not yet confirmed by the server. */
    public long inFlightBytes() {
        return inFlightBytes;
    }

    public int pendingActions() {
        return pending.size();
    }

    public boolean sending() {
        return !outbound.isEmpty();
    }

    public ActionSubmission offer(String action, byte[] bytes) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(bytes);
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Action sequence exhausted; reopen the menu");
        var value = new Pending(++sequence, action, bytes.clone());
        pending.put(sequence, value);
        outbound.addLast(value);
        return new ActionSubmission(sequence, ActionFailure.NONE, bytes.length, -1);
    }

    public void pump(long tick, Consumer<Part> send) {
        if (tick < admissionTick) throw new IllegalArgumentException("Non-monotonic action clock");
        if (tick != admissionTick) {
            admissionTick = tick;
            startedThisTick = 0;
        }
        while (!outbound.isEmpty()) {
            var next = outbound.peekFirst();
            if (!next.started && startedThisTick >= ACTIONS_PER_TICK) break;
            int size = Math.min(next.data.length - next.offset, FRAGMENT_BYTES);
            if (inFlightBytes + size > WINDOW_BYTES) break;
            boolean single = !next.started && size == next.data.length;
            byte[] part = Arrays.copyOfRange(next.data, next.offset, next.offset + size);
            send.accept(new Part(next.sequence, next.action, next.data.length, next.offset, part, single));
            if (!next.started) {
                next.started = true;
                startedThisTick++;
            }
            next.offset += size;
            inFlightBytes += size;
            if (next.offset == next.data.length) {
                outbound.removeFirst();
                next.data = null;
            }
        }
    }

    /** The server has received the first {@code received} bytes of action {@code sequence}. */
    public void progress(long sequence, int received) {
        var value = pending.get(sequence);
        if (value == null) return;
        int confirmed = Math.min(received, value.offset);
        if (confirmed <= value.confirmed) return;
        inFlightBytes -= confirmed - value.confirmed;
        value.confirmed = confirmed;
    }

    /** A reply confirms the whole action, and retires its unsent tail if the server refused an early fragment. */
    public String reply(long sequence) {
        var value = pending.remove(sequence);
        if (value == null) return null;
        inFlightBytes -= value.offset - value.confirmed;
        if (value.data != null) {
            outbound.remove(value);
            value.data = null;
        }
        return value.action;
    }

    /** Cancels this menu's work. */
    public void clear() {
        pending.clear();
        outbound.clear();
        inFlightBytes = 0;
    }
}
