package dev.composemc.sync.action;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.composemc.sync.transport.TokenBucket;

/** Owner-thread FIFO with connection-scoped credit and distinct queue/send/reply deadlines. */
public final class ActionQueue {
    public record Part(long sequence, String action, int total, int offset, byte[] data, boolean single) {}
    public record Expired(long sequence, String action, ActionFailure failure, long elapsed, long limit) {}
    private static final class Pending {
        final long sequence, queuedAt;
        final String action;
        byte[] data;
        int offset;
        boolean started, sent;
        long progress;
        Pending(long sequence, String action, byte[] data, long tick) {
            this.sequence = sequence; this.action = action; this.data = data;
            queuedAt = progress = tick;
        }
    }
    private final ActionLimits limits;
    private final TokenBucket bandwidth;
    private final LinkedHashMap<Long, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<Pending> outbound = new ArrayDeque<>();
    private long sequence, queuedBytes, admissionTick = Long.MIN_VALUE;
    private int startedThisTick;

    public ActionQueue(ActionLimits limits, TokenBucket bandwidth) {
        this.limits = Objects.requireNonNull(limits); this.bandwidth = Objects.requireNonNull(bandwidth);
    }
    public long queuedBytes() { return queuedBytes; }
    public long remainingBytes() { return limits.maxQueuedBytes() - queuedBytes; }
    public int pendingActions() { return pending.size(); }
    public boolean sending() { return !outbound.isEmpty(); }

    /** Null means there is room to try encoding; offer performs the final byte check. */
    public ActionSubmission admission() {
        if (pending.size() >= limits.maxPendingActions())
            return ActionSubmission.rejected(ActionFailure.PENDING_ACTIONS, (long) pending.size() + 1, limits.maxPendingActions());
        if (remainingBytes() == 0)
            return ActionSubmission.rejected(ActionFailure.QUEUE_BYTES, queuedBytes, limits.maxQueuedBytes());
        return null;
    }
    public ActionSubmission offer(String action, byte[] bytes, long tick) {
        Objects.requireNonNull(action); Objects.requireNonNull(bytes);
        var refusal = admission();
        if (refusal != null) return refusal;
        if (bytes.length > remainingBytes())
            return ActionSubmission.rejected(ActionFailure.QUEUE_BYTES, queuedBytes + bytes.length, limits.maxQueuedBytes());
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Action sequence exhausted; reopen the menu");
        var value = new Pending(++sequence, action, bytes.clone(), tick);
        pending.put(sequence, value); outbound.addLast(value); queuedBytes += bytes.length;
        return new ActionSubmission(sequence, ActionFailure.NONE, bytes.length, limits.maxQueuedBytes());
    }

    public void pump(long tick, BooleanSupplier writable, Consumer<Part> send) {
        if (tick < admissionTick) throw new IllegalArgumentException("Non-monotonic action clock");
        if (tick != admissionTick) { admissionTick = tick; startedThisTick = 0; }
        while (!outbound.isEmpty() && writable.getAsBoolean()) {
            var next = outbound.peekFirst();
            if (!next.started && startedThisTick >= limits.actionsPerTick()) break;
            int remaining = next.data.length - next.offset;
            int available = bandwidth.available(tick);
            if (remaining > 0 && available == 0) break;
            int size = Math.min(remaining, Math.min(limits.fragmentBytes(), available));
            boolean single = !next.started && size == next.data.length;
            byte[] part = Arrays.copyOfRange(next.data, next.offset, next.offset + size);
            send.accept(new Part(next.sequence, next.action, next.data.length, next.offset, part, single));
            bandwidth.consume(size, tick);
            if (!next.started) { next.started = true; startedThisTick++; }
            next.offset += size; next.progress = tick;
            if (next.offset == next.data.length) {
                outbound.removeFirst(); queuedBytes -= next.data.length;
                next.data = null; next.sent = true;
            }
        }
    }

    /** A reply also retires any unsent tail if the server refused an early fragment. */
    public String reply(long sequence) {
        var value = pending.remove(sequence);
        if (value == null) return null;
        if (!value.sent) { outbound.remove(value); queuedBytes -= value.data.length; value.data = null; }
        return value.action;
    }

    public Expired expired(long tick) {
        for (var value : pending.values()) {
            long elapsed = tick - (value.started ? value.progress : value.queuedAt);
            long limit = !value.started ? limits.queueTimeoutTicks() : value.sent ? limits.replyTimeoutTicks() : limits.progressTimeoutTicks();
            if (elapsed >= limit) return new Expired(value.sequence, value.action,
                    !value.started ? ActionFailure.QUEUE_TIMEOUT : value.sent ? ActionFailure.REPLY_TIMEOUT : ActionFailure.SEND_TIMEOUT,
                    elapsed, limit);
        }
        return null;
    }
    /** Cancels this menu's work, preserving the externally owned connection credit. */
    public void clear() { pending.clear(); outbound.clear(); queuedBytes = 0; }
}
