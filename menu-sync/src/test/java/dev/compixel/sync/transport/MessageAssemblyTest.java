package dev.compixel.sync.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.SyncException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MessageAssemblyTest {
    @Test
    void fragmentsDoNotExposePartialMessages() {
        byte[] data = new byte[50000];
        Arrays.fill(data, (byte) 7);
        try (var rx = new MessageAssembly(data.length, 65536)) {
            assertNull(rx.append(0, Arrays.copyOfRange(data, 0, 16384)));
            assertEquals(16384, rx.received());
            assertNull(rx.append(16384, Arrays.copyOfRange(data, 16384, 32768)));
            assertArrayEquals(data, rx.append(32768, Arrays.copyOfRange(data, 32768, data.length)));
            assertThrows(SyncException.class, () -> rx.append(0, new byte[] {1}));
        }
    }

    @Test
    void invalidOrderingAndSizeNeverPublish() {
        assertThrows(IllegalArgumentException.class, () -> new MessageAssembly(Integer.MAX_VALUE, 1024));
        try (var rx = new MessageAssembly(2, 10)) {
            assertThrows(SyncException.class, () -> rx.append(1, new byte[] {1}));
            assertThrows(SyncException.class, () -> rx.append(0, new byte[3]));
            assertNull(rx.append(0, new byte[] {1}));
            assertThrows(SyncException.class, () -> rx.append(0, new byte[] {2}));
            assertThrows(SyncException.class, () -> rx.append(1, new byte[0]));
            assertArrayEquals(new byte[] {1, 2}, rx.append(1, new byte[] {2}));
        }
    }
}
