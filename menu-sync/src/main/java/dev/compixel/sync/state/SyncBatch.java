package dev.compixel.sync.state;

import dev.compixel.sync.SyncException;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Framing over a reliable, ordered transport. Adapters additionally bind this to a menu and request nonce. */
public record SyncBatch(long revision, boolean snapshot, int index, int operations, boolean last, byte[] data) {
    public SyncBatch {
        if (revision < 1 || index < 0 || operations < 1 || data == null || data.length < 1)
            throw new SyncException("Invalid batch header");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public int dataSize() {
        return data.length;
    }

    byte[] rawData() {
        return data;
    }

    public void write(DataOutput out) throws IOException {
        out.writeLong(revision);
        out.writeBoolean(snapshot);
        out.writeInt(index);
        out.writeInt(operations);
        out.writeBoolean(last);
        out.writeInt(data.length);
        out.write(data);
    }

    public static SyncBatch read(DataInput in, int maximumBatchBytes) throws IOException {
        long revision = in.readLong();
        boolean snapshot = SyncCodecs.BOOLEAN.read(in);
        int index = in.readInt(), operations = in.readInt();
        boolean last = SyncCodecs.BOOLEAN.read(in);
        int length = in.readInt();
        if (length < 1 || length > maximumBatchBytes)
            throw new IOException("Invalid sync batch length: " + length + ", limit=" + maximumBatchBytes);
        byte[] data = DeclaredBytes.read(in, length, "sync batch");
        return new SyncBatch(revision, snapshot, index, operations, last, data);
    }
}
