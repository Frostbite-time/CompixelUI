package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.slots.ComposeMenuSlots
import dev.compixel.forge.slots.SlotBehaviorScreen
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.host.GameThread
import dev.compixel.host.UiStateBinding
import dev.compixel.host.gameState
import dev.compixel.slots.SlotBehavior
import dev.compixel.slots.SlotIntent
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import kotlin.math.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import org.lwjgl.glfw.GLFW

/**
 * Compose layout/drawing inside the native container lifecycle, with replaceable inventory behavior. A subclass draws
 * its [Content] with the slots, reads the menu and the game with [gameState], whose read blocks run on the game thread,
 * and changes them with tasks it posts to [gameThread].
 *
 * Tasks queued while Compose handles an input event run before the event returns, as vanilla widgets act inside their
 * input handlers, and the game state refreshes before it returns; those queued at other times run at the next tick. The
 * content reads each game state immediately when it first composes, then at its interval and after tasks ran. A screen
 * that only covers this one while the menu stays open, such as a recipe viewer, leaves its content as it was until it
 * shows again and refreshes. When the content uses ScreenTransition, closing gives the player control back at once
 * while the content plays its exit above the game.
 */
abstract class ComposeInventoryScreen<M : AbstractContainerMenu>(
    protected val container: M,
    title: Component,
    val inventory: ComposeMenuSlots<M> = ComposeMenuSlots(container),
    nativeItemOptions: NativeItemOptions = NativeItemOptions(cacheCapacity = 256),
    nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    design: UiDesign = OreDesign(),
    minimumUiDensity: Float = 1f,
) : SlotBehaviorScreen<M>(container, checkNotNull(Minecraft.getInstance().player).inventory, title) {
    /** The content's game state, opened and closed with each Compose session. */
    internal val contentState = UiStateBinding()
    private val layer =
        ComposeLayer(
            guiUnitsPerDp = ::guiUnitsPerDp,
            nativeItemOptions = nativeItemOptions,
            minimumUiDensity = minimumUiDensity,
            nativeDrawingOptions = nativeDrawingOptions,
            design = design,
            windowFocused = { isUiWindowFocused() },
            prepareFrameContent = { inventory.refreshAfterLayout() },
            contentState = contentState,
            closeHost = ::onClose,
            openColorEditor = { Minecraft.getInstance().setScreen(ColorEditorScreen(this, design)) },
        ) {
            Content(inventory)
        }
    // Set by onClose: the player's own inventory menu stays the open menu after its screen closes.
    private var closing = false
    private var menuEnded = false
    internal val session
        get() = layer.session

    val hasTextInputFocus
        get() = layer.hasTextInputFocus || (focused as? EditBox)?.canConsumeInput() == true

    val rendererStatistics
        get() = layer.rendererStatistics

    val nativeDrawingStatistics
        get() = layer.nativeDrawingStatistics

    val nativeItemStatistics
        get() = layer.nativeItemStatistics

    val frameProfiler
        get() = layer.frameProfiler

    private var nativePress = false
    private var dragButton = 0
    private var nativeCapture: GuiEventListener? = null
    private var duringDrag = false
    private var nativeCoordinates = emptyMap<Pair<Int, Int>, Int>()

    /**
     * The content, composed again only when state it reads changes. Read the menu and the game through [gameState] and
     * change them through [gameThread], and otherwise use [slots] and immutable values prepared during construction:
     * never read game objects such as the menu here.
     */
    @Composable protected abstract fun Content(slots: ComposeMenuSlots<M>)

    /**
     * Closes the screen on the game thread, as [onClose] does: before the input event during which it was called
     * returns, otherwise at the next tick. Call it on the game thread, for example from a close button in the content.
     */
    protected fun requestClose() = layer.requestClose()

    /**
     * Runs the content's tasks on the game thread: before the input event that queued them returns, otherwise at the
     * next tick, and the game state refreshes after them. False while the screen is not shown or 64 tasks are waiting.
     *
     * For example: `onClick = { gameThread.post { container.clearGrid() } }`.
     */
    val gameThread: GameThread
        get() = contentState.gameThread

    /**
     * Runs once on the game thread after the screen has closed for good and let go of its menu, for example to save
     * what the player entered. A screen that only covers this one, such as a recipe viewer, does not end it; the menu
     * closing while it covers this one does.
     */
    protected open fun menuClosed() {}

    protected open fun inventoryTick() {}

    protected open fun isUiWindowFocused() = Minecraft.getInstance().isWindowActive

    /**
     * GUI units per Compose dp for the current window framebuffer size and Minecraft [guiScale].
     *
     * Defaults to `1f` (matching vanilla Minecraft's GUI scale). Must return a finite positive value. Override to adapt
     * scale dynamically, for example stepping down one integer scale while keeping density at least `1`: `if
     * (guiScale > 1) (guiScale - 1f) / guiScale else 1f`.
     */
    protected open fun guiUnitsPerDp(framebufferWidth: Int, framebufferHeight: Int, guiScale: Int): Float = 1f

    final override fun containerTick() {
        layer.tick()
        inventoryTick()
        inventory.refresh()
        layer.tickContent()
    }

    override fun slotBehavior(slot: Slot?): SlotBehavior = inventory.adapter.behavior(slot)

    override fun localSlotAction(slot: Slot?, action: SlotIntent.Local) = inventory.adapter.localAction(slot, action)

    override fun executeInventoryClick(slotId: Int, button: Int, type: ClickType) {
        if (
            Minecraft.getInstance().player?.containerMenu === container &&
                inventory.interactionsEnabled &&
                !sendingAction()
        )
            inventory.adapter.execute(slotId, button, type)
    }

    override fun slotAt(x: Double, y: Double): Slot? =
        if (inventory.interactionsEnabled) container.slots.getOrNull(inventory.slotAt(x, y)) else null

    // The first renderable, so container background hooks draw beneath Compose and widgets above it.
    private val composeRenderable = Renderable { graphics, _, _, _ -> layer.render(graphics, width, height) }

    override fun init() {
        cancelInteraction()
        CoveredScreens.remove(this)
        ScreenExits.reclaim(layer)
        super.init()
        renderables.remove(composeRenderable)
        renderables.add(0, composeRenderable)
        layer.open(width, height)
        updateViewport()
        // Lay out now, so initialization hooks already see the Compose container area.
        layer.layout()
        updateSlotCoordinates()
    }

    private fun updateViewport() {
        inventory.viewport(layer.currentMetrics(width, height))
    }

    private fun cancelInteraction() {
        nativePress = false
        isQuickCrafting = false
        quickCraftSlots.clear()
        cancelSlotClickCapture()
        inventory.reset()
    }

    private fun mirrorDrag() {
        inventory.nativeInteraction(
            dragButton,
            if (isQuickCrafting) quickCraftSlots.mapTo(linkedSetOf()) { it.index } else emptySet(),
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateViewport()
        if (!isUiWindowFocused() || !inventory.interactionsEnabled || sendingAction()) cancelInteraction()
        mirrorDrag()
        inventory.refresh()
        // Lay out before the native background hooks, so they see this frame's container geometry.
        layer.prepare(graphics, width, height)
        updateSlotCoordinates()
        // Preserve native render hooks used by recipe overlays and other container integrations.
        super.render(graphics, mouseX, mouseY, partialTick)
        inventory.overlay(graphics, mouseX, mouseY, drawCursor = false)
    }

    override fun renderBackground(graphics: GuiGraphics) {}

    override fun renderBg(
        graphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {} // Drawn by composeRenderable.

    // Native slot and container coordinates are drawn at exactly guiScale pixels per GUI unit, like the frame. Mouse
    // tests keep Minecraft's rounded mapping: isHovering and hasClickedOutside compare with inventory.bounds.
    private fun updateSlotCoordinates() {
        inventory.renderAreaBounds()?.let { bounds ->
            leftPos = floor(bounds.left).toInt()
            topPos = floor(bounds.top).toInt()
            imageWidth = ceil(bounds.right - bounds.left).toInt()
            imageHeight = ceil(bounds.bottom - bounds.top).toInt()
        }
        val positions = HashMap<Pair<Int, Int>, Int>(inventory.visibleSlotIds.size)
        for (slot in container.slots) {
            val bounds = inventory.renderBounds(slot.index)
            if (bounds == null) {
                slot.x = -10000
                slot.y = -10000
            } else {
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
            if (!inventory.interactionsEnabled || duringDrag && !inventory.adapter.canDragTo(container.slots[id]))
                return false
            val bounds = inventory.bounds(id) ?: return false
            return mouseX >= bounds.left && mouseX < bounds.right && mouseY >= bounds.top && mouseY < bounds.bottom
        }
        return super.isHovering(x, y, w, h, mouseX, mouseY)
    }

    override fun hasClickedOutside(mouseX: Double, mouseY: Double, left: Int, top: Int, button: Int): Boolean {
        val bounds = inventory.areaBounds() ?: return super.hasClickedOutside(mouseX, mouseY, left, top, button)
        return mouseX < bounds.left || mouseX >= bounds.right || mouseY < bounds.top || mouseY >= bounds.bottom
    }

    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(x, y, button)) {
            nativeCapture = child
            setFocused(child)
            return true
        }
        setFocused(null)
        val area = inventory.areaBounds()
        val outside = area != null && (x < area.left || x >= area.right || y < area.top || y >= area.bottom)
        nativePress = !sendingAction() && inventory.interactionsEnabled && (inventory.slotAt(x, y) >= 0 || outside)
        dragButton = if (button == 0 || button == 1) button else 2
        val handled = if (nativePress) super.mouseClicked(x, y, button) else false
        val ui = layer.press(x, y, button)
        mirrorDrag()
        inventory.refresh()
        return layer.handled(ui) || handled
    }

    override fun mouseMoved(x: Double, y: Double) {
        inventory.move(x, y)
        val ui = layer.move(x, y)
        super.mouseMoved(x, y)
        layer.handled(ui)
    }

    override fun mouseDragged(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean {
        nativeCapture?.let {
            return it.mouseDragged(x, y, button, dx, dy)
        }
        val handled =
            if (nativePress && inventory.interactionsEnabled && !sendingAction()) {
                duringDrag = true
                try {
                    super.mouseDragged(x, y, button, dx, dy)
                } finally {
                    duringDrag = false
                }
            } else false
        mirrorDrag()
        inventory.refresh()
        // The hovered slot follows the pointer while a button is held, as it does when the pointer moves.
        inventory.move(x, y)
        return layer.handled(layer.move(x, y)) || handled
    }

    override fun mouseReleased(x: Double, y: Double, button: Int): Boolean {
        nativeCapture?.let {
            nativeCapture = null
            it.mouseReleased(x, y, button)
            return true
        }
        val handled =
            if (nativePress && inventory.interactionsEnabled && !sendingAction()) super.mouseReleased(x, y, button)
            else false
        nativePress = false
        mirrorDrag()
        inventory.refresh()
        return layer.handled(layer.release(x, y, button)) || handled
    }

    override fun mouseScrolled(x: Double, y: Double, sy: Double): Boolean {
        for (child in children().asReversed()) if (child.isMouseOver(x, y) && child.mouseScrolled(x, y, sy)) return true
        cancelInteraction()
        // Calling the native hook also lets container integrations consume wheel gestures.
        return super.mouseScrolled(x, y, sy) || layer.handled(layer.scroll(x, y, 0.0, sy))
    }

    // Every handler reports real consumption, so keys and text that nothing uses reach the Post events.
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (focused?.keyPressed(keyCode, scanCode, modifiers) == true) return true
        if (
            sendingAction() &&
                !hasTextInputFocus &&
                Minecraft.getInstance()
                    .options
                    .keyInventory
                    .isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.getKey(keyCode, scanCode))
        ) {
            onClose()
            return true
        }
        if (hasTextInputFocus || !inventory.interactionsEnabled || sendingAction())
            return layer.handled(layer.keyPressed(keyCode, scanCode, modifiers)) || closeOnEscape(keyCode)
        return super.keyPressed(keyCode, scanCode, modifiers) ||
            layer.handled(layer.keyPressed(keyCode, scanCode, modifiers))
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        super.keyReleased(keyCode, scanCode, modifiers) ||
            layer.handled(layer.keyReleased(keyCode, scanCode, modifiers))

    override fun charTyped(character: Char, modifiers: Int): Boolean =
        focused?.charTyped(character, modifiers) == true || layer.handled(layer.charTyped(character))

    /** Where native key handling is bypassed, an Escape that Compose leaves still closes the screen. */
    private fun closeOnEscape(keyCode: Int): Boolean {
        if (keyCode != GLFW.GLFW_KEY_ESCAPE || !shouldCloseOnEsc()) return false
        onClose()
        return true
    }

    private fun sendingAction() =
        (container as? dev.compixel.forge.sync.SyncedMenu)?.menuSync()?.isSendingAction() == true

    override fun onClose() {
        cancelInteraction()
        closing = true
        super.onClose()
    }

    override fun removed() {
        cancelInteraction()
        val menuStillOpen = !closing && menuOpen()
        closing = false
        if (menuStillOpen) {
            // A recipe viewer or another screen only covers this one and may show it again, as it was.
            layer.suspend()
            CoveredScreens.add(this, ::menuOpen, ::closeCovered)
            return
        }
        // The menu is gone; the content can still draw its last slots while it plays its exit.
        inventory.freeze()
        try {
            ScreenExits.close(layer) { inventory.close() }
        } finally {
            endMenu()
        }
    }

    private fun menuOpen() = Minecraft.getInstance().player?.containerMenu === container

    // The menu closed while another screen covered this one, which will not show again.
    private fun closeCovered() {
        try {
            layer.close()
        } finally {
            inventory.close()
            endMenu()
        }
    }

    // The screen lets go of its menu once, even when something removes it again.
    private fun endMenu() {
        if (menuEnded) return
        menuEnded = true
        super.removed()
        menuClosed()
    }
}
