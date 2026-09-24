package dev.composemc.sync;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StringCodecTest {
    static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    static byte[] declared(int length) { return ByteBuffer.allocate(4).putInt(length).array(); }
    static byte[] encode(SyncCodec<String> codec, String value) throws IOException {
        var bytes = new ByteArrayOutputStream(); codec.write(new DataOutputStream(bytes), value); return bytes.toByteArray();
    }

    @Test void aShortBodyCannotAllocateItsDeclaredStringLength() throws Exception {
        int length = 64 * 1024 * 1024;
        var codec = SyncCodecs.string(length);
        // Link the rejection path first so one-time bootstrap work is not mistaken for decoding memory.
        assertThrows(EOFException.class, () -> codec.read(input(declared(length))));
        var body = input(declared(length));
        long allocated = Allocations.measure(() -> assertThrows(EOFException.class, () -> codec.read(body)));
        assertTrue(allocated < 1024 * 1024, "A 4-byte body declaring 64 MiB allocated " + allocated + " bytes");
    }

    @Test void lengthsBeyondTheLimitOrTheReceivedBytesAreRejected() throws IOException {
        var codec = SyncCodecs.string(64 * 1024);
        assertThrows(IOException.class, () -> codec.read(input(declared(-1))));
        // Fully present bytes still cannot exceed the configured limit.
        assertThrows(IOException.class, () -> codec.read(input(ByteBuffer.allocate(4 + 64 * 1024 + 1).putInt(64 * 1024 + 1).array())));
        byte[] complete = encode(codec, "x".repeat(50_000));
        for (int kept : new int[]{4, 5, 4 + 4096, 4 + 4097, complete.length - 1})
            assertThrows(EOFException.class, () -> codec.read(input(Arrays.copyOf(complete, kept))), "kept=" + kept);
    }

    @Test void valuesSpanningSeveralReadStepsRoundTrip() throws IOException {
        var codec = SyncCodecs.string(4 * 1024 * 1024);
        for (int size : new int[]{0, 1, 4095, 4096, 4097, 8193, 3 * 1024 * 1024 + 1}) {
            String value = "x".repeat(size);
            assertEquals(value, codec.read(input(encode(codec, value))), "size=" + size);
        }
        // Multi-byte sequences straddle the growth boundaries; decoding still sees one contiguous value.
        String mixed = "中文😀é".repeat(50_000);
        var body = input(encode(codec, mixed));
        assertEquals(mixed, codec.read(body));
        assertEquals(0, body.available());
    }
}
