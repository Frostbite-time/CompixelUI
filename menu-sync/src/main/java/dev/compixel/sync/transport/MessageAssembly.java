package dev.compixel.sync.transport;

import dev.compixel.sync.SyncException;
import java.io.ByteArrayOutputStream;

/** One message over a reliable, ordered transport. Allocation grows with received data. */
public final class MessageAssembly implements AutoCloseable {
    private final int total;
    private ByteArrayOutputStream bytes;
    private boolean closed;

    public MessageAssembly(int total, int maximum) {
        if (total < 1 || total > maximum) throw new IllegalArgumentException("Invalid message bounds");
        this.total = total;
        bytes = new ByteArrayOutputStream(Math.min(total, 4096));
    }

    /** Bytes received so far, while the message is incomplete. */
    public int received() {
        if (closed) throw new IllegalStateException("Message assembly is closed");
        return bytes.size();
    }

    /** Appends the next part; returns the whole message once it is complete, otherwise null. */
    public byte[] append(int offset, byte[] part) {
        if (closed || offset != bytes.size() || part.length == 0 || part.length > total - bytes.size())
            throw new SyncException("Invalid message fragment");
        bytes.write(part, 0, part.length);
        if (bytes.size() != total) return null;
        closed = true;
        byte[] result = bytes.toByteArray();
        bytes = null;
        return result;
    }

    @Override
    public void close() {
        closed = true;
        bytes = null;
    }
}
