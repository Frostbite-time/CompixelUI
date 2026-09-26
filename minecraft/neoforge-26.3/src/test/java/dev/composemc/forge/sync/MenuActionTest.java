package dev.composemc.forge.sync;

import dev.composemc.sync.SyncCodecs;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MenuActionTest {
    @Test void invalidOrTrailingWireDataNeverInvokesTheHandler() throws Exception {
        var calls = new AtomicInteger();
        var action = MenuAction.<AbstractContainerMenu, Integer>of("mode", SyncCodecs.INT, (menu, player, value) -> { calls.incrementAndGet(); return value >= 0 && value < 3; });
        assertThrows(IOException.class, () -> action.apply(null, null, new byte[]{1}));
        assertThrows(IOException.class, () -> action.apply(null, null, new byte[]{0, 0, 0, 1, 2}));
        assertEquals(0, calls.get());
        assertFalse(action.apply(null, null, action.encode(99)));
        assertTrue(action.apply(null, null, action.encode(1)));
        assertEquals(2, calls.get());
    }
    @Test void actionEncodingHasItsOwnSmallPayloadBudget() throws Exception {
        var action = MenuAction.<AbstractContainerMenu, String>of("text", SyncCodecs.string(16384), (menu, player, value) -> true);
        assertEquals(8192, action.encode("x".repeat(8188)).length);
        assertThrows(IOException.class, () -> action.encode("x".repeat(8189)));
    }
    @Test void consumerCanOptIntoBodiesLargerThanTwoMiB() throws Exception {
        var action = MenuAction.<AbstractContainerMenu, String>of("large", SyncCodecs.string(4 * 1024 * 1024),
                4 * 1024 * 1024, (menu, player, value) -> true);
        assertEquals(3 * 1024 * 1024 + 4, action.encode("x".repeat(3 * 1024 * 1024)).length);
        var failure = assertThrows(dev.composemc.sync.SizeLimitException.class, () -> action.encode("x".repeat(200), 100));
        assertEquals(100, failure.limit()); assertEquals(204, failure.actual());
    }
}
