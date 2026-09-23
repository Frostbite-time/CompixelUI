package dev.composemc.neoforge

import androidx.compose.runtime.Composable
import dev.composemc.neoforge.slots.NeoForgeSlotBehaviorScreen
import dev.composemc.slots.SlotBehavior
import dev.composemc.slots.SlotIntent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import kotlin.math.*

/** Compose layout/drawing inside the native container lifecycle, with replaceable inventory behavior. */
open class NeoForgeComposeInventoryScreen<M : AbstractContainerMenu>(
    protected val container: M,
    title: Component,
    val inventory: ComposeMenuSlots<M> = ComposeMenuSlots(container),
    content: @Composable (ComposeMenuSlots<M>) -> Unit,
) : NeoForgeSlotBehaviorScreen<M>(container, checkNotNull(Minecraft.getInstance().player).inventory, title) {
    private val layer = object : NeoForgeComposeScreen(title, nativeItemOptions = NativeItemOptions(cacheCapacity = 256, preparationsPerFrame = 16), content = { content(inventory) }) {
        override fun isUiWindowFocused() = this@NeoForgeComposeInventoryScreen.isUiWindowFocused()
        override fun onClose() = this@NeoForgeComposeInventoryScreen.onClose()
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
    override fun executeInventoryClick(slotId: Int, button: Int, type: ContainerInput) {
        if (Minecraft.getInstance().player?.containerMenu === container && inventory.interactionsEnabled && !sendingAction())
            inventory.adapter.execute(slotId, button, type)
    }
    override fun slotAt(x: Double, y: Double): Slot? = if (inventory.interactionsEnabled) container.slots.getOrNull(inventory.slotAt(x, y)) else null

    override fun init() { cancelInteraction(); super.init(); layer.init(width, height); updateViewport() }
    private fun updateViewport() {
        val window = Minecraft.getInstance().window
        inventory.viewport(width.coerceAtLeast(1), height.coerceAtLeast(1), window.width.coerceAtLeast(1), window.height.coerceAtLeast(1))
    }
    private fun cancelInteraction() { nativePress = false; isQuickCrafting = false; quickCraftSlots.clear(); cancelSlotClickCapture(); inventory.reset() }
    private fun mirrorDrag() { inventory.nativeInteraction(nativePress, dragButton, if (isQuickCrafting) quickCraftSlots.mapTo(linkedSetOf()) { it.index } else emptySet()) }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateViewport()
        if (!isUiWindowFocused() || !inventory.interactionsEnabled || sendingAction()) cancelInteraction()
        mirrorDrag(); inventory.refresh()
        layer.extractRenderState(graphics, mouseX, mouseY, partialTick)
        updateSlotCoordinates()
        // Preserve native extraction hooks used by recipe overlays and other container integrations.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        inventory.overlay(graphics, mouseX, mouseY, drawCursor = false)
    }
    private fun updateSlotCoordinates() {
        inventory.areaBounds()?.let { bounds ->
            leftPos = floor(bounds.left).toInt(); topPos = floor(bounds.top).toInt()
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
    // Compose owns the clipped visual content, while AbstractContainerScreen
    // still owns menu extraction, recipe overlays and interaction state.
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {}
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {}
    override fun isHovering(x: Int, y: Int, w: Int, h: Int, mouseX: Double, mouseY: Double): Boolean {
        val id = if (w == 16 && h == 16) nativeCoordinates[x to y] else null
        if (id != null) {
            if (!inventory.interactionsEnabled || duringDrag && !inventory.adapter.canDragTo(container.slots[id])) return false
            val bounds = inventory.bounds(id) ?: return false
            return mouseX >= bounds.left && mouseX < bounds.right && mouseY >= bounds.top && mouseY < bounds.bottom
        }
        return super.isHovering(x, y, w, h, mouseX, mouseY)
    }
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x(); val y = event.y(); val button = event.button()
        for (child in children().asReversed()) if (child.mouseClicked(event, doubleClick)) { nativeCapture = child; setFocused(child); return true }
        setFocused(null)
        val area = inventory.areaBounds()
        val outside = area != null && (x < area.left || x >= area.right || y < area.top || y >= area.bottom)
        nativePress = !sendingAction() && inventory.interactionsEnabled && (inventory.slotAt(x, y) >= 0 || outside)
        dragButton = when (button) {
            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT -> 0
            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT -> 1
            else -> 2
        }
        val handled = if (nativePress) super.mouseClicked(event, doubleClick) else false
        val ui = layer.mouseClicked(event, doubleClick)
        mirrorDrag(); inventory.refresh()
        return handled || ui
    }
    override fun mouseMoved(x: Double, y: Double) { inventory.move(x, y); layer.mouseMoved(x, y); super.mouseMoved(x, y) }
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        nativeCapture?.let { return it.mouseDragged(event, dx, dy) }
        val handled = if (nativePress && inventory.interactionsEnabled && !sendingAction()) {
            duringDrag = true
            try { super.mouseDragged(event, dx, dy) } finally { duringDrag = false }
        } else false
        mirrorDrag(); inventory.refresh()
        return layer.mouseDragged(event, dx, dy) || handled
    }
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        nativeCapture?.let { nativeCapture = null; it.mouseReleased(event); return true }
        val handled = if (nativePress && inventory.interactionsEnabled && !sendingAction()) super.mouseReleased(event) else false
        nativePress = false; mirrorDrag(); inventory.refresh()
        return layer.mouseReleased(event) || handled
    }
    override fun mouseScrolled(x: Double, y: Double, sx: Double, sy: Double): Boolean {
        for (child in children().asReversed()) if (child.isMouseOver(x, y) && child.mouseScrolled(x, y, sx, sy)) return true
        cancelInteraction()
        // Calling the native hook also lets container integrations consume wheel gestures.
        return super.mouseScrolled(x, y, sx, sy) || layer.mouseScrolled(x, y, sx, sy)
    }
    override fun keyPressed(event: KeyEvent): Boolean {
        if (focused?.keyPressed(event) == true) return true
        if (sendingAction() && !hasTextInputFocus && Minecraft.getInstance().options.keyInventory.isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.getKey(event))) { onClose(); return true }
        if (hasTextInputFocus || !inventory.interactionsEnabled || sendingAction()) return layer.keyPressed(event)
        return super.keyPressed(event) || layer.keyPressed(event)
    }
    override fun keyReleased(event: KeyEvent): Boolean = super.keyReleased(event) || layer.keyReleased(event)
    override fun charTyped(event: CharacterEvent): Boolean = focused?.charTyped(event) == true || layer.charTyped(event)
    private fun sendingAction() = (container as? dev.composemc.neoforge.sync.SyncedMenu)?.menuSync()?.isSendingAction() == true
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
