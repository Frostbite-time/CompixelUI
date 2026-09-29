package dev.compixel.forge.slots;

import dev.compixel.slots.SlotBehavior;
import dev.compixel.slots.SlotGesture;
import dev.compixel.slots.SlotIntent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/**
 * Incremental adoption for any native container screen. Standard policies retain Minecraft's
 * pointer/keyboard state machine, prediction and packets. Override policy and local actions to
 * replace a click; override executeInventoryClick only for a consumer-owned inventory protocol.
 * This is native-screen interop, not yet a Compose inventory screen.
 */
public abstract class SlotBehaviorScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> {
    private int capturedButtons;

    protected SlotBehaviorScreen(M menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /** Slot can be null for outside clicks and drag start/end. Policies should be cached values. */
    protected SlotBehavior slotBehavior(Slot slot) {
        return SlotBehavior.standard();
    }

    /** A local UI action is consumed even if no handler is installed. */
    protected void localSlotAction(Slot slot, SlotIntent.Local action) {}

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        Slot slot = slotAt(x, y);
        // Widgets layered above a slot keep priority. An active native drag keeps its owner.
        boolean overWidget = children().stream().anyMatch(child -> child.isMouseOver(x, y));
        if (!overWidget && !isQuickCrafting && !hasShiftDown() && (button == 0 || button == 1) && slot != null) {
            SlotIntent intent = slotBehavior(slot).resolve(new SlotGesture(SlotGesture.Kind.CLICK, button));
            if (!(intent instanceof SlotIntent.Inventory)) {
                capturedButtons |= 1 << button;
                if (intent instanceof SlotIntent.Local local) localSlotAction(slot, local);
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (isSlotClickCaptured(button)) return true;
        return super.mouseDragged(x, y, button, dx, dy);
    }

    /** Consumers with additional drag handlers must check this before handling that button. */
    protected final boolean isSlotClickCaptured(int button) {
        return button >= 0 && button <= 1 && (capturedButtons & (1 << button)) != 0;
    }
    /** Release a local gesture capture when the host changes layout, opens a modal or loses focus. */
    protected final void cancelSlotClickCapture() {
        capturedButtons = 0;
    }

    /** Public/protected slot geometry only; no access transformer or private vanilla hook. */
    protected Slot slotAt(double x, double y) {
        for (Slot slot : menu.slots) if (slot.isActive() && isHovering(slot.x, slot.y, 16, 16, x, y)) return slot;
        return null;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (isSlotClickCaptured(button)) {
            capturedButtons &= ~(1 << button);
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
        int id = slot == null ? slotId : slot.index;
        NativeSlotClicks.dispatch(
                id,
                button,
                type,
                slotBehavior(slot),
                this::executeInventoryClick,
                action -> localSlotAction(slot, action));
    }

    /** Game-thread inventory execution; default uses vanilla prediction and the existing MC connection. */
    protected void executeInventoryClick(int slotId, int button, ClickType type) {
        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
        super.slotClicked(slot, slotId, button, type);
    }

    @Override
    public void removed() {
        capturedButtons = 0;
        super.removed();
    }
}
