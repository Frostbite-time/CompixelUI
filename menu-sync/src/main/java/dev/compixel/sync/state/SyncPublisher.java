package dev.compixel.sync.state;

import dev.compixel.sync.SyncException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Owner-thread publisher. Encodes one record at a time and keeps at most {@link #WINDOW_BYTES} of batches in flight
 * that the receiver has not acknowledged.
 */
public final class SyncPublisher<M> implements AutoCloseable {
    /** The largest batch. */
    public static final int BATCH_BYTES = 128 * 1024;
    /** Batch bytes sent and not yet acknowledged, at most. */
    public static final int WINDOW_BYTES = 512 * 1024;

    private final Thread owner = Thread.currentThread();
    private final SyncSchema<M> schema;
    private SyncSchema.State baseline;
    private Pending pending;
    private long revision, lastPump = Long.MIN_VALUE;
    private boolean closed;
    private long batches, bytes, operations;
    // Sizes of the pending update's unacknowledged batches, oldest first.
    private final ArrayDeque<Integer> unacknowledged = new ArrayDeque<>();
    private long inFlightBytes;

    private final class Pending {
        final SyncSchema.State target;
        final List<SyncSchema.Operation> ops;
        final boolean snapshot;
        int operation, cursor, sequence, acknowledged = -1;
        byte[] record;
        boolean ended;

        Pending(SyncSchema.State target, List<SyncSchema.Operation> ops) {
            this.target = target;
            this.ops = ops;
            snapshot = baseline == null;
        }
    }

    public SyncPublisher(SyncSchema<M> schema) {
        this.schema = schema;
    }

    public long revision() {
        return revision;
    }

    public long sentBatches() {
        return batches;
    }

    public long sentBytes() {
        return bytes;
    }

    public long sentOperations() {
        return operations;
    }

    public boolean busy() {
        return pending != null;
    }

    /** Batch bytes sent and not yet acknowledged. */
    public long inFlightBytes() {
        return inFlightBytes;
    }

    /** At most once per logical tick. Intermediate model states coalesce while an update is in flight. */
    public void pump(M model, long tick, Consumer<SyncBatch> transport) {
        checkOwner();
        if (closed) return;
        if (tick < lastPump) throw new IllegalArgumentException("Non-monotonic sync clock");
        if (tick == lastPump) return;
        lastPump = tick;
        if (pending == null) {
            SyncSchema.State target = schema.capture(model, baseline);
            List<SyncSchema.Operation> ops = schema.difference(baseline, target);
            if (ops.isEmpty()) {
                baseline = target;
                return;
            }
            pending = new Pending(target, ops);
        }
        while (pending != null && !pending.ended && inFlightBytes + BATCH_BYTES <= WINDOW_BYTES) {
            Pending p = pending;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            while (buffer.size() < BATCH_BYTES) {
                if (p.record == null) {
                    if (p.operation == p.ops.size()) break;
                    byte[] value = schema.encode(p.ops.get(p.operation++));
                    p.record = ByteBuffer.allocate(4 + value.length)
                            .putInt(value.length)
                            .put(value)
                            .array();
                    p.cursor = 0;
                    operations++;
                }
                int length = Math.min(BATCH_BYTES - buffer.size(), p.record.length - p.cursor);
                buffer.write(p.record, p.cursor, length);
                p.cursor += length;
                if (p.cursor == p.record.length) p.record = null;
            }
            p.ended = p.record == null && p.operation == p.ops.size();
            byte[] data = buffer.toByteArray();
            SyncBatch batch = new SyncBatch(revision + 1, p.snapshot, p.sequence++, p.ops.size(), p.ended, data);
            unacknowledged.addLast(data.length);
            inFlightBytes += data.length;
            batches++;
            bytes += data.length;
            transport.accept(batch);
        }
    }

    /** Acknowledges every batch of the pending update up to {@code index}. */
    public void acknowledge(long acknowledgedRevision, int index) {
        checkOwner();
        if (closed || pending == null || acknowledgedRevision <= revision) return;
        if (acknowledgedRevision != revision + 1 || index < 0 || index >= pending.sequence)
            throw new SyncException("Invalid sync acknowledgement");
        if (index <= pending.acknowledged) return;
        for (int i = pending.acknowledged; i < index; i++) inFlightBytes -= unacknowledged.removeFirst();
        pending.acknowledged = index;
        if (pending.ended && index == pending.sequence - 1) {
            baseline = pending.target;
            revision++;
            pending = null;
        }
    }

    @Override
    public void close() {
        checkOwner();
        closed = true;
        baseline = null;
        pending = null;
        unacknowledged.clear();
        inFlightBytes = 0;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Publisher used outside owning thread");
    }
}
