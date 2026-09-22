package dev.composemc.slots;

import java.util.Objects;

/** Resolution is pure. The owning thread dispatches inventory operations or local UI actions. */
public sealed interface SlotIntent {
    /** The adapter/consumer executes this through its existing, server-validated inventory protocol. */
    record Inventory(SlotGesture gesture) implements SlotIntent {
        public Inventory { Objects.requireNonNull(gesture, "gesture"); }
    }

    /** A consumer-defined local action, such as opening a context menu. Never an automatic C2S call. */
    record Local(String action) implements SlotIntent {
        public Local {
            Objects.requireNonNull(action, "action");
            if (action.isBlank() || action.length() > 128) throw new IllegalArgumentException("Invalid local action");
        }
    }

    enum Ignore implements SlotIntent { INSTANCE }
}
