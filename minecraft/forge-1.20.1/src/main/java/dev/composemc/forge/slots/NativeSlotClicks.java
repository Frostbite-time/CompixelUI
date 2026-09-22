package dev.composemc.forge.slots;

import dev.composemc.slots.SlotBehavior;
import dev.composemc.slots.SlotGesture;
import dev.composemc.slots.SlotIntent;
import net.minecraft.world.inventory.ClickType;
import java.util.Objects;
import java.util.function.Consumer;

/** Public translation between platform-neutral gestures and the 1.20.1 menu click protocol. */
public final class NativeSlotClicks {
    private NativeSlotClicks() {}

    @FunctionalInterface
    public interface InventoryExecutor { void execute(int slotId, int button, ClickType type); }

    public record Click(int button, ClickType type) {}

    public static SlotGesture gesture(int button, ClickType type) {
        Objects.requireNonNull(type);
        return switch (type) {
            case PICKUP -> new SlotGesture(SlotGesture.Kind.CLICK, button);
            case QUICK_MOVE -> new SlotGesture(SlotGesture.Kind.QUICK_MOVE, button);
            case SWAP -> new SlotGesture(SlotGesture.Kind.SWAP, button);
            // Key-bound clone may arrive with button 0; the semantic gesture is always clone.
            case CLONE -> new SlotGesture(SlotGesture.Kind.CLONE, 2);
            case THROW -> new SlotGesture(SlotGesture.Kind.DROP, button);
            case PICKUP_ALL -> new SlotGesture(SlotGesture.Kind.COLLECT, button);
            case QUICK_CRAFT -> {
                if (button < 0 || button > 10 || (button & 3) == 3)
                    throw new IllegalArgumentException("Invalid quick-craft mask");
                var phase = switch (button & 3) {
                    case 0 -> SlotGesture.Kind.DRAG_START;
                    case 1 -> SlotGesture.Kind.DRAG_ADD;
                    default -> SlotGesture.Kind.DRAG_END;
                };
                yield new SlotGesture(phase, button >> 2);
            }
        };
    }

    public static Click click(SlotGesture gesture) {
        Objects.requireNonNull(gesture);
        return switch (gesture.kind()) {
            case CLICK -> new Click(gesture.button(), ClickType.PICKUP);
            case QUICK_MOVE -> new Click(gesture.button(), ClickType.QUICK_MOVE);
            case SWAP -> new Click(gesture.button(), ClickType.SWAP);
            case CLONE -> new Click(2, ClickType.CLONE);
            case DROP -> new Click(gesture.button(), ClickType.THROW);
            case COLLECT -> new Click(gesture.button(), ClickType.PICKUP_ALL);
            case DRAG_START -> new Click(gesture.button() << 2, ClickType.QUICK_CRAFT);
            case DRAG_ADD -> new Click((gesture.button() << 2) | 1, ClickType.QUICK_CRAFT);
            case DRAG_END -> new Click((gesture.button() << 2) | 2, ClickType.QUICK_CRAFT);
        };
    }

    /**
     * Call on the game thread. InventoryExecutor owns prediction/networking; Local is never sent.
     * Pass the stable menu slot ID (including the outside sentinel), not a sorted/display index.
     */
    public static void dispatch(int slotId, int button, ClickType type, SlotBehavior behavior,
                                InventoryExecutor inventory, Consumer<SlotIntent.Local> local) {
        SlotIntent intent = behavior.resolve(gesture(button, type));
        if (intent instanceof SlotIntent.Inventory operation) {
            var click = click(operation.gesture());
            inventory.execute(slotId, click.button(), click.type());
        } else if (intent instanceof SlotIntent.Local action) {
            local.accept(action);
        }
    }
}
