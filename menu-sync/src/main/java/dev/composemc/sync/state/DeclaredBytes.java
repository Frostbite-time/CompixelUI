package dev.composemc.sync.state;

import java.io.DataInput;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/** Buffers for peer-declared lengths. Capacity follows bytes that actually arrived, never the declaration alone. */
final class DeclaredBytes {
    private static final int FIRST_STEP = 4096;

    static byte[] start(int declared) {
        return new byte[Math.min(declared, FIRST_STEP)];
    }

    /** Call when {@code bytes} is full but short of {@code declared}; at most doubles what arrived. */
    static byte[] grow(byte[] bytes, int declared) {
        return Arrays.copyOf(bytes, (int) Math.min(declared, 2L * bytes.length));
    }

    static byte[] read(DataInput in, int declared, String value) throws IOException {
        byte[] bytes = start(declared);
        try {
            for (int received = 0; ; bytes = grow(bytes, declared)) {
                in.readFully(bytes, received, bytes.length - received);
                if ((received = bytes.length) == declared) return bytes;
            }
        } catch (EOFException truncated) {
            throw new EOFException("Truncated " + value + ": declared=" + declared + " bytes");
        }
    }

    private DeclaredBytes() {}
}
