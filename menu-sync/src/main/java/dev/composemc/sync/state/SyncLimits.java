package dev.composemc.sync.state;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;
import dev.composemc.sync.transport.TransferBudget;

/** Consumer-owned S2C limits. A transport separately validates its physical packet envelope. */
public record SyncLimits(int batchBytes, TransferBudget bandwidth, int inFlightBatches,
                         int maxRecordBytes, long maxUpdateBytes, int maxEntries, long timeoutTicks) {
    public static final SyncLimits DEFAULT = new SyncLimits(128 * 1024,
        new TransferBudget(32 * 1024, 4L * 1024 * 1024, 256 * 1024), 16,
        1024 * 1024, 32L * 1024 * 1024, 100_000, 200);

    public SyncLimits {
        Objects.requireNonNull(bandwidth);
        if (batchBytes < 1 || inFlightBatches < 1 || maxRecordBytes < 5
                || maxRecordBytes > Integer.MAX_VALUE - Integer.BYTES
                || maxUpdateBytes < (long) maxRecordBytes + Integer.BYTES
                || maxEntries < 1 || timeoutTicks < 1)
            throw new IllegalArgumentException("Invalid state limits (positive sizes/counts, record + framing must fit update)");
    }
    public void write(DataOutput out) throws IOException {
        out.writeInt(batchBytes); bandwidth.write(out); out.writeInt(inFlightBatches);
        out.writeInt(maxRecordBytes); out.writeLong(maxUpdateBytes); out.writeInt(maxEntries); out.writeLong(timeoutTicks);
    }
    public static SyncLimits read(DataInput in) throws IOException {
        return new SyncLimits(in.readInt(), TransferBudget.read(in), in.readInt(), in.readInt(), in.readLong(), in.readInt(), in.readLong());
    }
}
