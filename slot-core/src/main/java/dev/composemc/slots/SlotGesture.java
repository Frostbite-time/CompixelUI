package dev.composemc.slots;

import java.util.Objects;

/** A recognized gesture. Drag phases carry the pointer button, never platform wire bit masks. */
public record SlotGesture(Kind kind, int button) {
    public enum Kind {
        CLICK,
        QUICK_MOVE,
        SWAP,
        CLONE,
        DROP,
        DRAG_START,
        DRAG_ADD,
        DRAG_END,
        COLLECT
    }

    public SlotGesture {
        Objects.requireNonNull(kind, "kind");
        boolean valid = switch (kind) {
            case CLICK, QUICK_MOVE, DROP, COLLECT -> button == 0 || button == 1;
            case SWAP -> button >= 0 && button < 9 || button == 40;
            case CLONE -> button == 2;
            case DRAG_START, DRAG_ADD, DRAG_END -> button >= 0 && button <= 2;
        };
        if (!valid) throw new IllegalArgumentException("Invalid button for " + kind + ": " + button);
    }
}
