package dev.compixel.slots;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, composable gesture policy; contains no mutable inventory or screen state. */
public final class SlotBehavior {
    private static final SlotBehavior STANDARD = new SlotBehavior(Map.of());
    private final Map<SlotGesture, SlotIntent> overrides;

    private SlotBehavior(Map<SlotGesture, SlotIntent> overrides) {
        this.overrides = Map.copyOf(overrides);
    }

    public static SlotBehavior standard() {
        return STANDARD;
    }

    public SlotIntent resolve(SlotGesture gesture) {
        Objects.requireNonNull(gesture, "gesture");
        SlotIntent result = overrides.get(gesture);
        return result != null ? result : new SlotIntent.Inventory(gesture);
    }

    /** Replace exactly one gesture. All other mappings are retained. */
    public SlotBehavior with(SlotGesture gesture, SlotIntent intent) {
        var copy = new HashMap<>(overrides);
        copy.put(Objects.requireNonNull(gesture), Objects.requireNonNull(intent));
        return new SlotBehavior(copy);
    }

    /**
     * Replace an ordinary click with a local action or ignore. Also disables dragging with that
     * button. Shift clicks, other buttons, swapping and collection retain their existing rules.
     * Hosts must intercept this local/ignored click on press, before starting platform drag logic,
     * and capture its matching drag/release even when the pointer leaves the original slot.
     */
    public SlotBehavior replaceClick(int button, SlotIntent intent) {
        Objects.requireNonNull(intent);
        if (intent instanceof SlotIntent.Inventory)
            throw new IllegalArgumentException("Use with() for inventory remapping");
        SlotBehavior result = with(new SlotGesture(SlotGesture.Kind.CLICK, button), intent);
        for (var kind : new SlotGesture.Kind[] {
            SlotGesture.Kind.DRAG_START, SlotGesture.Kind.DRAG_ADD, SlotGesture.Kind.DRAG_END
        }) result = result.with(new SlotGesture(kind, button), SlotIntent.Ignore.INSTANCE);
        return result;
    }
}
