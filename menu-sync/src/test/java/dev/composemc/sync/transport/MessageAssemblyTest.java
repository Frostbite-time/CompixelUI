package dev.composemc.sync.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.composemc.sync.SyncException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MessageAssemblyTest {
    @Test
    void fragmentsDoNotExposePartialMessagesAndTimeoutReleasesState() {
        byte[] data = new byte[50000];
        Arrays.fill(data, (byte) 7);
        try (var rx = new MessageAssembly(data.length, 65536, 0)) {
            assertNull(rx.append(0, Arrays.copyOfRange(data, 0, 16384), 1));
            assertFalse(rx.expired(201, 200));
            assertTrue(rx.expired(202, 200));
            assertNull(rx.append(16384, Arrays.copyOfRange(data, 16384, 32768), 3));
            assertArrayEquals(data, rx.append(32768, Arrays.copyOfRange(data, 32768, data.length), 4));
            assertThrows(SyncException.class, () -> rx.append(0, new byte[] {1}, 5));
        }
    }

    @Test
    void invalidOrderingSizeAndClockNeverPublish() {
        assertThrows(IllegalArgumentException.class, () -> new MessageAssembly(Integer.MAX_VALUE, 1024, 0));
        try (var rx = new MessageAssembly(2, 10, 0)) {
            assertThrows(SyncException.class, () -> rx.append(1, new byte[] {1}, 1));
            assertThrows(SyncException.class, () -> rx.append(0, new byte[3], 1));
            assertNull(rx.append(0, new byte[] {1}, 1));
            assertThrows(SyncException.class, () -> rx.append(0, new byte[] {2}, 2));
            assertThrows(SyncException.class, () -> rx.append(1, new byte[0], 2));
            assertThrows(SyncException.class, () -> rx.append(1, new byte[] {2}, 0));
            assertArrayEquals(new byte[] {1, 2}, rx.append(1, new byte[] {2}, 2));
        }
    }
}
