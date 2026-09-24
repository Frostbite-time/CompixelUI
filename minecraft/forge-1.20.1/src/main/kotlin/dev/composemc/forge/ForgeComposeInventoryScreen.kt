package dev.composemc.forge

import androidx.compose.runtime.Composable
import dev.composemc.forge.slots.ForgeSlotBehaviorScreen
import dev.composemc.slots.SlotBehavior
import dev.composemc.slots.SlotIntent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import kotlin.math.*

/** Compose layout/drawing inside the native container lifecycle, with replaceable inventory behavior. */
open class ForgeComposeInventoryScreen<M : AbstractContainerMenu>(
    protected val container: M,
    title: Component,
    val inventory: ComposeMenuSlots<M> = ComposeMenuSlots(container),
    content: @Composable (ComposeMenuSlots<M>) -> Unit,
) : ForgeSlotBehaviorScreen<M>(container, checkNotNull(Minecraft.getInstance().player).inventory, title) {
    private val layer = object : ForgeComposeScreen(title, nativeItemOptions = NativeItemOptions(cacheCapacity = 256), content = { content(inventory) }) {
        override fun isUiWindowFocused() = this@ForgeComposeInventoryScreen.isUiWindowFocused()
        override fun onClose() = this@ForgeComposeInventoryScreen.onClose()
        override fun prepareFrameContent() = inventory.refreshAfterLayout()
    }
    val hasTextInputFocus get() = layer.hasTextInputFocus || (focused as? EditBox)?.canConsumeInput() == true
    val rendererStatistics get() = layer.rendererStatistics
    val nativeItemStatistics get() = layer.nativeItemStatistics
    val frameProfiler get() = layer.frameProfiler
    private var nativePress = false
    private var dragButton = 0
    private var nativeCapture: GuiEventListener? = null
    private var duringDrag = false
    private var nativeCoordinates = emptyMap<Pair<Int, Int>, Int>()
    protected open fun inventoryTick() {}
    protected open fun isUiWindowFocused() = Minecraft.getInstance().isWindowActive
    final override fun containerTick() { layer.tick(); inventoryTick(); inventory.refresh() }
    override fun slotBehavior(slot: Slot?): SlotBehavior = inventory.adapter.behavior(slot)
    override fun localSlotAction(slot: Slot?, action: SlotIntent.Local) = inventory.adapter.localAction(slot, action)
    override fun executeInventoryClick(slotId: Int, button: Int, type: ClickType) {
        if (Minecraft.getInstance().player?.containerMenu === container && inventory.interactionsEnabled && !sendingAction())
            inventory.adapter.execute(slotId, button, type)
    }
    override fun slotAt(x: Double, y: Double): Slot? = if (inventory.interactionsEnabled) container.slots.getOrNull(inventory.slotAt(x, y)) else null

    override fun init() { cancelInteraction(); super.init(); layer.init(Minecraft.getInstance(), width, height); updateViewport() }
    private fun updateViewport() {
        val window = Minecraft.getInstance().window
        inventory.viewport(width.coerceAtLeast(1), height.coerceAtLeast(1), window.width.coerceAtLeast(1), window.height.coerceAtLeast(1))
    }
    private fun cancelInteraction() { nativePress = false; isQuickCrafting = false; quickCraftSlots.clear(); cancelSlotClickCapture(); inventory.reset() }
    private fun mirrorDrag() { inventory.nativeInteraction(nativePress, dragButton, if (isQuickCrafting) quickCraftSlots.mapTo(linkedSetOf()) { it.index } else emptySet()) }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateViewport()
        if (!isUiWindowFocused() || !inventory.interactionsEnabled || sendingAction()) cancelInteraction()
        mirrorDrag(); inventory.refresh()
        // Preserve native render hooks used by recipe overlays and other container integrations.
        super.render(graphics, mouseX, mouseY, partialTick)
        inventory.overlay(graphics, mouseX, mouseY, drawCursor = false)
    }
    override fun renderBackground(graphics: GuiGraphics) {}
    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        layer.render(graphics, mouseX, mouseY, partialTick)
        inventory.areaBounds()?.let { bounds ->
            leftPos = floor(bounds.left).toInt(); topPos = floor(bounds.top).toInt()
            imageWidth = ceil(bounds.right - bounds.left).toInt(); imageHeight = ceil(bounds.bottom - bounds.top).toInt()
        }
        val positions = HashMap<Pair<Int, Int>, Int>(inventory.visibleSlotIds.size)
        for (slot in container.slots) {
            val bounds = inventory.bounds(slot.index)
            if (bounds == null) { slot.x = -10000; slot.y = -10000 }
            else {
                slot.x = ((bounds.left + bounds.right) / 2 - leftPos - 8).roundToInt()
                slot.y = ((bounds.top + bounds.bottom) / 2 - topPos - 8).roundToInt()
                positions[slot.x to slot.y] = slot.index
            }
        }
        nativeCoordinates = positions
    }
    override fun renderSlot(graphics: GuiGraphics, slot: Slot) {} // The Compose slot owns its clipped icon and count.
    override fun getSlotColor(index: Int): Int = 0
    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {}
    override fun isHovering(x: Int, y: Int, w: Int, h: Int, mouseX: Double, mouseY: Double): Boolean {
        val id = if (w == 16 && h == 16) nativeCoordinates[x to y] else null
        if (id != null) {
            if (!inventory.interactionsEnabled || duringDrag && !inventory.adapter.canDragTo(container.slots[id])) return false
            val bounds = inventory.bounds(id) ?: return false
            return mouseX >= bounds.left && mouseX < bounds.right && mouseY >= bounds.top && mouseY < bounds.bottom
        }
        return super.isHovering(x, y, w, h, mouseX, mouseY)
    }
    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(x, y, button)) { nativeCapture = child; setFocused(child); return true }
        setFocused(null)
        val area = inventory.areaBounds()
        val outside = area != null && (x < area.left || x >= area.right || y < area.top || y >= area.bottom)
        nativePress = !sendingAction() && inventory.interactionsEnabled && (inventory.slotAt(x, y) >= 0 || outside)
        dragButton = if (button == 0 || button == 1) button else 2
        val handled = if (nativePress) super.mouseClicked(x, y, button) else false
        val ui = layer.mouseClicked(x, y, button)
        mirrorDrag(); inventory.refresh()
        return handled || ui
    }
    override fun mouseMoved(x: Double, y: Double) { inventory.move(x, y); layer.mouseMoved(x, y); super.mouseMoved(x, y) }
    override fun mouseDragged(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean {
        nativeCapture?.let { return it.mouseDragged(x, y, button, dx, dy) }
        val handled = if (nativePress && inventory.interactionsEnabled && !sendingAction()) {
            duringDrag = true
            try { super.mouseDragged(x, y, button, dx, dy) } finally { duringDrag = false }
        } else false
        mirrorDrag(); inventory.refresh()
        return layer.mouseDragged(x, y, button, dx, dy) || handled
    }
    override fun mouseReleased(x: Double, y: Double, button: Int): Boolean {
        nativeCapture?.let { nativeCapture = null; it.mouseReleased(x, y, button); return true }
        val handled = if (nativePress && inventory.interactionsEnabled && !sendingAction()) super.mouseReleased(x, y, button) else false
        nativePress = false; mirrorDrag(); inventory.refresh()
        return layer.mouseReleased(x, y, button) || handled
    }
    override fun mouseScrolled(x: Double, y: Double, sy: Double): Boolean {
        for (child in children().asReversed()) if (child.isMouseOver(x, y) && child.mouseScrolled(x, y, sy)) return true
        cancelInteraction()
        // Calling the native hook also lets container integrations consume wheel gestures.
        return super.mouseScrolled(x, y, sy) || layer.mouseScrolled(x, y, sy)
    }
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (focused?.keyPressed(keyCode, scanCode, modifiers) == true) return true
        if (sendingAction() && !hasTextInputFocus && Minecraft.getInstance().options.keyInventory.isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.getKey(keyCode, scanCode))) { onClose(); return true }
        if (hasTextInputFocus || !inventory.interactionsEnabled || sendingAction()) return layer.keyPressed(keyCode, scanCode, modifiers)
        return super.keyPressed(keyCode, scanCode, modifiers) || layer.keyPressed(keyCode, scanCode, modifiers)
    }
    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = super.keyReleased(keyCode, scanCode, modifiers) || layer.keyReleased(keyCode, scanCode, modifiers)
    override fun charTyped(character: Char, modifiers: Int): Boolean = focused?.charTyped(character, modifiers) == true || layer.charTyped(character, modifiers)
    private fun sendingAction() = (container as? dev.composemc.forge.sync.SyncedMenu)?.menuSync()?.isSendingAction() == true
    override fun onClose() { cancelInteraction(); super.onClose() }
    override fun removed() {
        cancelInteraction()
        val menuStillOpen = Minecraft.getInstance().player?.containerMenu === container
        try { layer.removed() } finally {
            if (menuStillOpen) inventory.detachLayout() // A recipe overlay can return to this same Screen.
            else { inventory.close(); super.removed() }
        }
    }
}
