package dev.composemc.sync.state;

import dev.composemc.sync.SyncException;
import dev.composemc.sync.transport.TokenBucket;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owner-thread publisher. Encodes one record at a time and waits for cumulative batch ACKs. */
public final class SyncPublisher<M> implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final SyncSchema<M> schema;
    private final SyncLimits limits;
    private TokenBucket bandwidth;
    private SyncSchema.State baseline;
    private Pending pending;
    private long revision, lastProgress, lastPump = Long.MIN_VALUE;
    private boolean closed;
    private long batches, bytes, operations;

    private final class Pending {
        final SyncSchema.State target;
        final List<SyncSchema.Operation> ops;
        final boolean snapshot;
        int operation, cursor, sequence, acknowledged = -1;
        byte[] record;
        long encodedBytes;
        boolean ended;

        Pending(SyncSchema.State target, List<SyncSchema.Operation> ops) {
            this.target = target;
            this.ops = ops;
            snapshot = baseline == null;
        }
    }

    public SyncPublisher(SyncSchema<M> schema, SyncLimits limits) {
        this.schema = schema;
        this.limits = limits;
    }

    public SyncPublisher(SyncSchema<M> schema, SyncLimits limits, TokenBucket bandwidth) {
        this(schema, limits);
        this.bandwidth = bandwidth;
    }

    public SyncPublisher(SyncSchema<M> schema) {
        this(schema, SyncLimits.DEFAULT);
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

    public boolean expired(long tick) {
        return pending != null && tick - lastProgress > limits.timeoutTicks();
    }

    public int inFlight() {
        return pending == null ? 0 : pending.sequence - pending.acknowledged - 1;
    }

    /** At most once per logical tick. Intermediate model states coalesce while an update is in flight. */
    public void pump(M model, long tick, Consumer<SyncBatch> transport) {
        pump(model, tick, () -> true, transport);
    }

    /** Rechecks transport backpressure between batches; partial batches need no full-batch credit. */
    public void pump(M model, long tick, BooleanSupplier writable, Consumer<SyncBatch> transport) {
        checkOwner();
        if (closed) return;
        if (tick < lastPump) throw new IllegalArgumentException("Non-monotonic sync clock");
        if (tick == lastPump) return;
        lastPump = tick;
        if (bandwidth == null) bandwidth = new TokenBucket(limits.bandwidth(), tick);
        if (pending != null && tick - lastProgress > limits.timeoutTicks())
            throw new SyncException("Menu sync ACK timeout");
        if (pending == null) {
            SyncSchema.State target = schema.capture(model, baseline, limits);
            List<SyncSchema.Operation> ops = schema.difference(baseline, target);
            if (ops.isEmpty()) {
                baseline = target;
                return;
            }
            pending = new Pending(target, ops);
            lastProgress = tick;
        }
        while (pending != null
                && !pending.ended
                && inFlight() < limits.inFlightBatches()
                && bandwidth.available(tick) > 0
                && writable.getAsBoolean()) {
            Pending p = pending;
            int capacity = Math.min(limits.batchBytes(), bandwidth.available(tick));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(capacity, 4096));
            while (buffer.size() < capacity) {
                if (p.record == null) {
                    if (p.operation == p.ops.size()) break;
                    byte[] value = schema.encode(p.ops.get(p.operation++), limits.maxRecordBytes());
                    if (4L + value.length > limits.maxUpdateBytes() - p.encodedBytes)
                        throw new SyncException(
                                "Menu sync update byte limit exceeded: limit=" + limits.maxUpdateBytes());
                    p.encodedBytes += 4L + value.length;
                    p.record = ByteBuffer.allocate(4 + value.length)
                            .putInt(value.length)
                            .put(value)
                            .array();
                    p.cursor = 0;
                    operations++;
                }
                int length = Math.min(capacity - buffer.size(), p.record.length - p.cursor);
                buffer.write(p.record, p.cursor, length);
                p.cursor += length;
                if (p.cursor == p.record.length) p.record = null;
            }
            p.ended = p.record == null && p.operation == p.ops.size();
            byte[] data = buffer.toByteArray();
            SyncBatch batch = new SyncBatch(revision + 1, p.snapshot, p.sequence++, p.ops.size(), p.ended, data);
            bandwidth.consume(data.length, tick);
            batches++;
            bytes += data.length;
            transport.accept(batch);
        }
    }

    public void acknowledge(long acknowledgedRevision, int index, long tick) {
        checkOwner();
        if (closed || pending == null || acknowledgedRevision <= revision) return;
        if (acknowledgedRevision != revision + 1 || index < 0 || index >= pending.sequence)
            throw new SyncException("Invalid sync acknowledgement");
        if (index <= pending.acknowledged) return;
        pending.acknowledged = index;
        lastProgress = tick;
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
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Publisher used outside owning thread");
    }
}
