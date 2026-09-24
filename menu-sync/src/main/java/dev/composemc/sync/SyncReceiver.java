package dev.composemc.sync;

import java.util.*;

/** Incremental bounded decoding with atomic publication at the last validated batch. */
public final class SyncReceiver<M> implements AutoCloseable {
    public enum Result { STAGED, COMMITTED, DUPLICATE, STALE }
    private final Thread owner = Thread.currentThread();
    private final SyncSchema<M> schema;
    private final SyncLimits limits;
    private SyncSchema.State current, staging;
    private long revision, incomingRevision, lastActivity, receivedBytes;
    private int nextIndex, expectedOperations, operations;
    private long entries;
    private boolean snapshot, closed;
    private boolean[] changed, initialized;
    private final byte[] lengthHeader = new byte[4];
    private int headerCursor, recordCursor, recordLength;
    private byte[] record;

    public SyncReceiver(SyncSchema<M> schema, SyncLimits limits) { this.schema = schema; this.limits = limits; }
    public SyncReceiver(SyncSchema<M> schema) { this(schema, SyncLimits.DEFAULT); }
    public long revision() { return revision; }
    public boolean receiving() { return staging != null; }
    public double progress() { return staging == null ? (revision > 0 ? 1 : 0) : (double) operations / Math.max(1, expectedOperations); }
    public boolean expired(long tick) { return staging != null && tick - lastActivity > limits.timeoutTicks(); }

    @SuppressWarnings("unchecked")
    public Result accept(M model, SyncBatch batch, long tick) {
        checkOwner();
        if (closed) throw new IllegalStateException("Receiver is closed");
        if (batch.rawData().length > limits.batchBytes()) throw new SyncException("Batch exceeds receiver budget");
        if (batch.revision() <= revision) return Result.STALE;
        if (staging == null) {
            if (batch.index() != 0 || batch.revision() != revision + 1 || (current == null && !batch.snapshot()))
                throw new SyncException("Snapshot or next revision required");
            if (batch.operations() > limits.maxEntries() * 2L + schema.bindings.size()) throw new SyncException("Operation count exceeds limit");
            staging = new SyncSchema.State(schema.bindings.size());
            snapshot = batch.snapshot(); incomingRevision = batch.revision(); expectedOperations = batch.operations();
            changed = new boolean[schema.bindings.size()]; initialized = new boolean[schema.bindings.size()];
            entries = 0;
            for (int i = 0; i < schema.bindings.size(); i++) {
                if (schema.bindings.get(i).keyCodec == null) staging.values[i] = snapshot ? null : current.values[i];
                else {
                    Map<Object,Object> map = snapshot ? schema.bindings.get(i).mapSnapshot ? SyncMap.empty() : new LinkedHashMap<>() : (Map<Object,Object>) current.values[i];
                    staging.values[i] = map; entries += map.size();
                }
            }
        }
        if (batch.revision() != incomingRevision || batch.snapshot() != snapshot || batch.operations() != expectedOperations)
            throw new SyncException("Mixed sync revisions");
        if (batch.index() < nextIndex) return Result.DUPLICATE;
        if (batch.index() != nextIndex) throw new SyncException("Out-of-order sync batch");
        lastActivity = tick;
        if (batch.rawData().length > limits.maxUpdateBytes() - receivedBytes) throw new SyncException("Update exceeds receiver byte budget");
        receivedBytes += batch.rawData().length;
        for (byte value : batch.rawData()) {
            if (record == null) {
                lengthHeader[headerCursor++] = value;
                if (headerCursor == 4) {
                    int length = ((lengthHeader[0] & 255) << 24) | ((lengthHeader[1] & 255) << 16) |
                        ((lengthHeader[2] & 255) << 8) | (lengthHeader[3] & 255);
                    if (length < 5 || length > limits.maxRecordBytes()) throw new SyncException("Invalid sync record length: " + length);
                    // A header alone must not reserve its declared record; capacity follows received bytes.
                    record = DeclaredBytes.start(length); recordLength = length; recordCursor = 0; headerCursor = 0;
                }
            } else {
                if (recordCursor == record.length) record = DeclaredBytes.grow(record, recordLength);
                record[recordCursor++] = value;
                if (recordCursor == recordLength) {
                    applyOperation(schema.decode(record)); record = null;
                    if (++operations > expectedOperations) throw new SyncException("Too many sync operations");
                }
            }
        }
        nextIndex++;
        if (!batch.last()) return Result.STAGED;
        if (record != null || headerCursor != 0 || operations != expectedOperations) throw new SyncException("Incomplete sync update");
        if (snapshot) for (boolean present : initialized) if (!present) throw new SyncException("Snapshot omitted a field");
        SyncSchema.State before = current == null ? schema.capture(model, null, limits) : current;
        try { schema.apply(model, staging, changed); }
        catch (RuntimeException failure) {
            try { schema.apply(model, before, changed); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw new SyncException("Sync setter failed", failure);
        }
        current = staging; revision = incomingRevision;
        clearStaging();
        return Result.COMMITTED;
    }

    @SuppressWarnings("unchecked")
    private void applyOperation(SyncSchema.Operation op) {
        int field = op.field();
        if (op.kind() == SyncSchema.SET) { staging.values[field] = op.value(); initialized[field] = true; }
        else {
            Map<Object,Object> map = (Map<Object,Object>) staging.values[field];
            if (snapshot && op.kind() != SyncSchema.CLEAR && !initialized[field]) throw new SyncException("Collection not initialized");
            if (schema.bindings.get(field).mapSnapshot) {
                var immutable = (SyncMap<Object,Object>)map;
                switch (op.kind()) {
                    case SyncSchema.CLEAR -> { entries -= immutable.size(); immutable = SyncMap.empty(); initialized[field] = true; }
                    case SyncSchema.PUT -> {
                        if (!immutable.containsKey(op.key()) && ++entries > limits.maxEntries()) throw new SyncException("Collection entry budget exceeded");
                        immutable = immutable.with(op.key(), op.value());
                    }
                    case SyncSchema.REMOVE -> {
                        var next = immutable.without(op.key()); if (next.size() != immutable.size()) entries--;
                        immutable = next;
                    }
                    default -> throw new SyncException("Invalid collection operation");
                }
                staging.values[field] = immutable; changed[field] = true; return;
            }
            if (!snapshot && !changed[field]) {
                if (op.kind() == SyncSchema.CLEAR) {
                    entries -= map.size();
                    staging.values[field] = new LinkedHashMap<>();
                    initialized[field] = changed[field] = true;
                    return;
                }
                map = new LinkedHashMap<>(map);
                staging.values[field] = map;
            }
            switch (op.kind()) {
                case SyncSchema.CLEAR -> { entries -= map.size(); map.clear(); initialized[field] = true; }
                case SyncSchema.PUT -> {
                    if (!map.containsKey(op.key()) && ++entries > limits.maxEntries()) throw new SyncException("Collection entry budget exceeded");
                    map.put(op.key(), op.value());
                }
                case SyncSchema.REMOVE -> { if (map.remove(op.key()) != null) entries--; }
                default -> throw new SyncException("Invalid collection operation");
            }
        }
        changed[field] = true;
    }
    private void clearStaging() {
        staging = null; record = null; changed = null; initialized = null;
        headerCursor = recordCursor = nextIndex = operations = expectedOperations = 0;
        receivedBytes = 0;
    }
    @Override public void close() { checkOwner(); closed = true; current = null; clearStaging(); }
    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Receiver used outside owning thread"); }
}
