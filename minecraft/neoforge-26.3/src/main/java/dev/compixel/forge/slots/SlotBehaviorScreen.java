package dev.compixel.forge.slots;

import com.mojang.blaze3d.platform.InputConstants;
import dev.compixel.slots.SlotBehavior;
import dev.compixel.slots.SlotGesture;
import dev.compixel.slots.SlotIntent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        int button = event.button();
        Slot slot = slotAt(x, y);
        // Widgets layered above a slot keep priority. An active native drag keeps its owner.
        boolean overWidget = children().stream().anyMatch(child -> child.isMouseOver(x, y));
        if (!overWidget
                && !isQuickCrafting
                && !event.hasShiftDown()
                && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)
                && slot != null) {
            SlotIntent intent = slotBehavior(slot)
                    .resolve(new SlotGesture(
                            SlotGesture.Kind.CLICK, button == InputConstants.MOUSE_BUTTON_LEFT ? 0 : 1));
            if (!(intent instanceof SlotIntent.Inventory)) {
                capturedButtons |= 1 << button;
                if (intent instanceof SlotIntent.Local local) localSlotAction(slot, local);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        int button = event.button();
        if (isSlotClickCaptured(button)) return true;
        return super.mouseDragged(event, dx, dy);
    }

    /** Consumers with additional drag handlers must check this before handling that button. */
    protected final boolean isSlotClickCaptured(int button) {
        return (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)
                && (capturedButtons & (1 << button)) != 0;
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
    public boolean mouseReleased(MouseButtonEvent event) {
        int button = event.button();
        if (isSlotClickCaptured(button)) {
            capturedButtons &= ~(1 << button);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int button, ContainerInput type) {
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
    protected void executeInventoryClick(int slotId, int button, ContainerInput type) {
        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
        super.slotClicked(slot, slotId, button, type);
    }

    @Override
    public void removed() {
        capturedButtons = 0;
        super.removed();
    }
}
