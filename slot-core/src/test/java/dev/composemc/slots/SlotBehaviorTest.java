package dev.composemc.slots;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SlotBehaviorTest {
    private static SlotGesture gesture(SlotGesture.Kind kind, int button) {
        return new SlotGesture(kind, button);
    }

    @Test
    void contextMenuReplacesOnlyRightClickAndRightDrag() {
        var original = SlotBehavior.standard();
        var menu = new SlotIntent.Local("example:context");
        var replaced = original.replaceClick(1, menu);
        assertEquals(menu, replaced.resolve(gesture(SlotGesture.Kind.CLICK, 1)));
        for (var kind : new SlotGesture.Kind[] {
            SlotGesture.Kind.CLICK,
            SlotGesture.Kind.QUICK_MOVE,
            SlotGesture.Kind.DRAG_START,
            SlotGesture.Kind.DRAG_ADD,
            SlotGesture.Kind.DRAG_END,
            SlotGesture.Kind.COLLECT
        }) {
            var left = gesture(kind, 0);
            assertEquals(new SlotIntent.Inventory(left), replaced.resolve(left));
        }
        assertInstanceOf(SlotIntent.Inventory.class, replaced.resolve(gesture(SlotGesture.Kind.QUICK_MOVE, 1)));
        for (var kind : new SlotGesture.Kind[] {
            SlotGesture.Kind.DRAG_START, SlotGesture.Kind.DRAG_ADD, SlotGesture.Kind.DRAG_END
        }) assertSame(SlotIntent.Ignore.INSTANCE, replaced.resolve(gesture(kind, 1)));
        assertInstanceOf(SlotIntent.Inventory.class, original.resolve(gesture(SlotGesture.Kind.CLICK, 1)));
    }

    @Test
    void independentRulesComposeWithoutMutatingEarlierPolicy() {
        var drop = gesture(SlotGesture.Kind.DROP, 1);
        var noDrop = SlotBehavior.standard().with(drop, SlotIntent.Ignore.INSTANCE);
        var contextual = noDrop.replaceClick(1, new SlotIntent.Local("context"));
        assertSame(SlotIntent.Ignore.INSTANCE, contextual.resolve(drop));
        assertInstanceOf(SlotIntent.Inventory.class, noDrop.resolve(gesture(SlotGesture.Kind.CLICK, 1)));
        var quickMove = gesture(SlotGesture.Kind.QUICK_MOVE, 0);
        var remapped = contextual.with(gesture(SlotGesture.Kind.CLICK, 0), new SlotIntent.Inventory(quickMove));
        assertEquals(new SlotIntent.Inventory(quickMove), remapped.resolve(gesture(SlotGesture.Kind.CLICK, 0)));
    }

    @Test
    void protocolMasksCannotLeakIntoSemanticDragButtons() {
        assertThrows(IllegalArgumentException.class, () -> gesture(SlotGesture.Kind.DRAG_ADD, 5));
        assertThrows(IllegalArgumentException.class, () -> gesture(SlotGesture.Kind.CLICK, -1));
        assertThrows(IllegalArgumentException.class, () -> gesture(SlotGesture.Kind.SWAP, 39));
        assertDoesNotThrow(() -> gesture(SlotGesture.Kind.SWAP, 40));
        assertThrows(
                IllegalArgumentException.class,
                () -> SlotBehavior.standard()
                        .replaceClick(1, new SlotIntent.Inventory(gesture(SlotGesture.Kind.CLICK, 0))));
    }
}
