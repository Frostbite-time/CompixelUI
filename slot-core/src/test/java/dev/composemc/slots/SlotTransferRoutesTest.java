package dev.composemc.slots;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlotTransferRoutesTest {
    @Test void orderedDestinationsAndReverseHotbarHaveStableIds() {
        var routes = SlotTransferRoutes.builder(41)
                .group("inventory", 0, 27).group("hotbar", 27, 36, true)
                .group("input", 36, 38).group("fuel", 38, 39).group("output", 39, 41)
                .route("inventory", "input", "fuel").route("hotbar", "input", "fuel")
                .route("output", "hotbar", "inventory").build();
        assertEquals(List.of(36, 37, 38), routes.targets(0));
        assertSame(routes.targets(0), routes.targets(26));
        assertEquals(List.of(35, 34, 33, 32, 31, 30, 29, 28, 27), routes.targets(40).subList(0, 9));
        assertTrue(routes.targets(38).isEmpty());
        assertTrue(routes.targets(-999).isEmpty());
        assertTrue(routes.targets(Integer.MAX_VALUE).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> routes.targets(0).add(39));
    }

    @Test void realConsumerAllocationIsIndependentOfPageAndSort() {
        var routes = SlotTransferRoutes.builder(927).group("player", 0, 36).group("storage", 36, 927)
                .route("player", "storage").route("storage", "player").build();
        assertEquals(891, routes.targets(0).size());
        assertEquals(36, routes.targets(0).get(0));
        assertEquals(926, routes.targets(35).get(890));
        assertEquals(36, routes.targets(926).size());
        assertSame(routes.targets(36), routes.targets(926));
    }

    @Test void ambiguousAndInvalidDeclarationsFailBeforeInventoryChanges() {
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).group("a", 0, 5).group("b", 4, 10).build());
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).group("a", 0, 11));
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).group("a", 0, 5).group("a", 5, 10));
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).group("a", 0, 5).route("a", "missing").build());
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).route("a", "a"));
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).route("a", "b", "b"));
        assertThrows(IllegalArgumentException.class, () -> SlotTransferRoutes.builder(10).route("a", "b").route("a", "c"));
    }

    @Test void builtRoutesAreIndependentOfSubsequentBuilderChanges() {
        var builder = SlotTransferRoutes.builder(4).group("a", 0, 2).group("b", 2, 4).route("a", "b");
        var first = builder.build();
        builder.route("b", "a");
        assertTrue(first.targets(2).isEmpty());
        assertEquals(List.of(0, 1), builder.build().targets(2));
    }
}
