package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.slots.ComposeMenuSlots
import dev.compixel.forge.slots.SlotBehaviorScreen
import dev.compixel.host.UiStateBinding
import dev.compixel.slots.SlotBehavior
import dev.compixel.slots.SlotIntent
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import dev.compixel.ui.theme.ThemeId
import java.util.concurrent.atomic.AtomicBoolean
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
 * Compose layout/drawing inside the native container lifecycle, with replaceable inventory behavior. A subclass reads
 * the menu into an immutable [snapshot], draws the latest one with the slots in [Content] and runs the actions its
 * content [send]s in [handle]. Actions sent while Compose handles an input event run before the event returns, as
 * vanilla widgets act inside their input handlers; actions sent at other times run at the next tick. The screen takes a
 * snapshot when it opens, after an input event's actions and every tick. Actions still pending when it is removed, for
 * example by a recipe viewer, are discarded, and a screen shown again starts from a new snapshot.
 */
abstract class ComposeInventoryScreen<M : AbstractContainerMenu, S, A>(
    protected val container: M,
    title: Component,
    val inventory: ComposeMenuSlots<M> = ComposeMenuSlots(container),
    theme: ThemeId = ThemeId.Default,
    design: UiDesign = OreDesign,
    nativeItemOptions: NativeItemOptions = NativeItemOptions(cacheCapacity = 256),
    nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
) : SlotBehaviorScreen<M>(container, checkNotNull(Minecraft.getInstance().player).inventory, title) {
    /** The content's game state, opened and closed with each Compose session. */
    internal val contentState = UiStateBinding<S, A>(::snapshot, ::handle)
    private val layer =
        ComposeLayer(
            nativeItemOptions = nativeItemOptions,
            nativeDrawingOptions = nativeDrawingOptions,
            theme = theme,
            design = design,
            windowFocused = { isUiWindowFocused() },
            prepareFrameContent = { inventory.refreshAfterLayout() },
            contentState = contentState,
        ) {
            Content(contentState.value, inventory)
        }
    private val closeRequested = AtomicBoolean()
    // Set by onClose: the player's own inventory menu stays the open menu after its screen closes.
    private var closing = false
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
     * Reads the menu on the game thread. It can run more than once per tick, so only read. Return an immutable value:
     * an equal snapshot leaves the content as it is.
     */
    protected abstract fun snapshot(): S

    /**
     * Runs an action from the content on the game thread, before the next snapshot: before the input event that sent it
     * returns, otherwise at the next tick. It may close the screen.
     */
    protected abstract fun handle(action: A)

    /**
     * The content for the latest [state], composed on the Compose thread. Use [state], [slots], [send] and immutable
     * values prepared during construction, never game objects such as the menu.
     */
    @Composable protected abstract fun Content(state: S, slots: ComposeMenuSlots<M>)

    /** Queues [action] for [handle] from any thread. False while the screen is not shown or 64 actions are waiting. */
    protected fun send(action: A): Boolean = contentState.send(action)

    /**
     * Closes the screen on the game thread, as [onClose] does: before the input event during which it was called
     * returns, otherwise at the next tick. Call it from any thread, for example from a close button in the content.
     */
    protected fun requestClose() = closeRequested.set(true)

    /**
     * Runs once on the game thread after the screen has closed for good and let go of its menu, for example to save
     * what the player entered. A screen that only covers this one, such as a recipe viewer, does not end it.
     */
    protected open fun menuClosed() {}

    protected open fun inventoryTick() {}

    protected open fun isUiWindowFocused() = Minecraft.getInstance().isWindowActive

    final override fun containerTick() {
        layer.tick()
        inventoryTick()
        inventory.refresh()
        contentState.tick()
        if (closeRequested.getAndSet(false)) onClose()
    }

    /**
     * Runs what the content asked for while Compose handled an input event, before the event returns, as vanilla
     * widgets act inside their input handlers: the actions it sent, then a close request. True when Compose [consumed]
     * the event or the content closed the screen, which then takes nothing more from the event.
     */
    private fun contentHandled(consumed: Boolean): Boolean {
        contentState.handleActions()
        if (closeRequested.getAndSet(false)) onClose()
        return consumed || !contentState.isOpen
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
            nativePress,
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
        return contentHandled(ui) || handled
    }

    override fun mouseMoved(x: Double, y: Double) {
        inventory.move(x, y)
        val ui = layer.move(x, y)
        super.mouseMoved(x, y)
        contentHandled(ui)
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
        return contentHandled(layer.move(x, y)) || handled
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
        return contentHandled(layer.release(x, y, button)) || handled
    }

    override fun mouseScrolled(x: Double, y: Double, sy: Double): Boolean {
        for (child in children().asReversed()) if (child.isMouseOver(x, y) && child.mouseScrolled(x, y, sy)) return true
        cancelInteraction()
        // Calling the native hook also lets container integrations consume wheel gestures.
        return super.mouseScrolled(x, y, sy) || contentHandled(layer.scroll(x, y, 0.0, sy))
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
            return contentHandled(layer.keyPressed(keyCode, scanCode, modifiers)) || closeOnEscape(keyCode)
        return super.keyPressed(keyCode, scanCode, modifiers) ||
            contentHandled(layer.keyPressed(keyCode, scanCode, modifiers))
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        super.keyReleased(keyCode, scanCode, modifiers) ||
            contentHandled(layer.keyReleased(keyCode, scanCode, modifiers))

    override fun charTyped(character: Char, modifiers: Int): Boolean =
        focused?.charTyped(character, modifiers) == true || contentHandled(layer.charTyped(character))

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
        closeRequested.set(false) // A request ends with the session that made it.
        val menuStillOpen = !closing && Minecraft.getInstance().player?.containerMenu === container
        closing = false
        try {
            layer.close()
        } finally {
            if (menuStillOpen) inventory.detachLayout() // A recipe overlay can return to this same Screen.
            else {
                inventory.close()
                super.removed()
                menuClosed()
            }
        }
    }
}
