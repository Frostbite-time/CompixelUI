package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.slots.ComposeMenuSlots
import dev.compixel.forge.slots.SlotBehaviorScreen
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.host.GameThread
import dev.compixel.host.UiStateBinding
import dev.compixel.slots.SlotBehavior
import dev.compixel.slots.SlotIntent
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import kotlin.math.*
import kotlin.properties.ReadOnlyProperty
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent // IME
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot

/**
 * Compose layout/drawing inside the native container lifecycle, with replaceable inventory behavior. A subclass draws
 * its [Content] with the slots, reads the menu and the game with [gameState], whose read blocks run on the game thread,
 * and changes them with tasks it posts to [gameThread]; it extends `ComposeInventoryScreen<M, Unit, Nothing>`. The
 * snapshot model, with [snapshot], [handle] and [send], is deprecated and removed in 0.2.0. Until then it mixes with
 * game state: [snapshot] reads the menu into one immutable value for [Content], and [handle] runs the actions the
 * content sends.
 *
 * Actions and tasks queued while Compose handles an input event run before the event returns, as vanilla widgets act
 * inside their input handlers, and the game state refreshes before it returns; those queued at other times run at the
 * next tick. The screen takes a snapshot and samples its member game state when it opens, before the first composition;
 * then it takes a snapshot every tick and samples each game state at its interval, and both after actions or tasks ran.
 * A screen that only covers this one while the menu stays open, such as a recipe viewer, leaves its content as it was
 * until it shows again and refreshes. When the content uses ScreenTransition, closing gives the player control back at
 * once while the content plays its exit above the game.
 */
abstract class ComposeInventoryScreen<M : AbstractContainerMenu, S, A>(
    protected val container: M,
    title: Component,
    val inventory: ComposeMenuSlots<M> = ComposeMenuSlots(container),
    nativeItemOptions: NativeItemOptions = NativeItemOptions(cacheCapacity = 256),
    nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    design: UiDesign = OreDesign(),
) : SlotBehaviorScreen<M>(container, checkNotNull(Minecraft.getInstance().player).inventory, title) {
    /** The content's game state, opened and closed with each Compose session. */
    @Suppress("DEPRECATION") internal val contentState = UiStateBinding<S, A>(::snapshot, ::handle)
    private val layer =
        ComposeLayer(
            nativeItemOptions = nativeItemOptions,
            nativeDrawingOptions = nativeDrawingOptions,
            design = design,
            windowFocused = { isUiWindowFocused() },
            prepareFrameContent = { inventory.refreshAfterLayout() },
            contentState = contentState,
            closeHost = ::onClose,
            openColorEditor = { Minecraft.getInstance().setScreen(ColorEditorScreen(this, design)) },
        ) {
            Content(contentState.value, inventory)
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
     * Reads the menu on the game thread for the snapshot model. It can run more than once per tick, so only read.
     * Return an immutable value: an equal snapshot leaves the content as it is. Screens that read the menu with
     * [gameState] leave it out.
     */
    @Deprecated("Read the menu with gameState; snapshot is removed in 0.2.0")
    @Suppress("UNCHECKED_CAST")
    protected open fun snapshot(): S = Unit as S

    /**
     * Runs an action from the content on the game thread, before the next snapshot: before the input event that sent it
     * returns, otherwise at the next tick. It may close the screen.
     */
    @Deprecated("Change the game in gameThread.post; handle is removed in 0.2.0")
    protected open fun handle(action: A) {}

    /**
     * The content, composed on the Compose thread. Read the menu and the game through [gameState] and change them
     * through [gameThread], and otherwise use [slots] and immutable values prepared during construction: never read
     * game objects such as the menu here. [state] is the latest [snapshot].
     */
    @Composable protected abstract fun Content(state: S, slots: ComposeMenuSlots<M>)

    /**
     * Queues [action] for [handle] from any thread. False while the screen is not shown or 64 actions and tasks are
     * waiting.
     */
    @Deprecated("Queue the work with gameThread.post; send is removed in 0.2.0")
    protected fun send(action: A): Boolean = contentState.send(action)

    /**
     * Closes the screen on the game thread, as [onClose] does: before the input event during which it was called
     * returns, otherwise at the next tick. Call it from any thread, for example from a close button in the content.
     */
    protected fun requestClose() = layer.requestClose()

    /**
     * Runs the content's tasks on the game thread, queued with its actions: before the input event that queued them
     * returns, otherwise at the next tick, and the game state refreshes after them. False while the screen is not shown
     * or 64 actions and tasks are waiting.
     *
     * For example: `onClick = { gameThread.post { container.clearGrid() } }`.
     */
    val gameThread: GameThread
        get() = contentState.gameThread

    /**
     * Game state for a member property, such as `private val progress by gameState { container.progress }`. [read] runs
     * on the game thread when the screen opens, before its first composition, then every [intervalTicks] client ticks
     * and after the content's actions and tasks. The property shows the latest result, recomposes its readers when it
     * changes and never runs [read] itself. Declare it while the screen is constructed, in the screen or in a state
     * object it creates; state that only part of the content needs can use the composable gameState with an initial
     * value. [read] only reads, and returns values or immutable data, never game objects.
     */
    fun <T> gameState(intervalTicks: Int = 1, read: () -> T): ReadOnlyProperty<Any?, T> =
        contentState.gameState(intervalTicks, read)

    /**
     * Runs once on the game thread after the screen has closed for good and let go of its menu, for example to save
     * what the player entered. A screen that only covers this one, such as a recipe viewer, does not end it; the menu
     * closing while it covers this one does.
     */
    protected open fun menuClosed() {}

    protected open fun inventoryTick() {}

    protected open fun isUiWindowFocused() = Minecraft.getInstance().isWindowActive

    final override fun containerTick() {
        layer.tick()
        inventoryTick()
        inventory.refresh()
        layer.tickContent()
    }

    override fun slotBehavior(slot: Slot?): SlotBehavior = inventory.adapter.behavior(slot)

    override fun localSlotAction(slot: Slot?, action: SlotIntent.Local) = inventory.adapter.localAction(slot, action)

    override fun executeInventoryClick(slotId: Int, button: Int, type: ContainerInput) {
        if (
            Minecraft.getInstance().player?.containerMenu === container &&
                inventory.interactionsEnabled &&
                !sendingAction()
        )
            inventory.adapter.execute(slotId, button, type)
    }

    override fun slotAt(x: Double, y: Double): Slot? =
        if (inventory.interactionsEnabled) container.slots.getOrNull(inventory.slotAt(x, y)) else null

    override fun init() {
        cancelInteraction()
        CoveredScreens.remove(this)
        ScreenExits.reclaim(layer)
        super.init()
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

    // As on 1.20.1/1.21.1, Compose supplies the container backdrop: skip the vanilla dimming,
    // but keep the deferred subtitle pass that vanilla runs here. The frame is laid out here,
    // so the background event that follows already sees this frame's container geometry.
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Minecraft.getInstance().gui.extractDeferredSubtitles()
        prepareFrame()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (!layer.framePrepared) prepareFrame()
        layer.render(graphics, width, height)
        // Preserve native extraction hooks used by recipe overlays and other container integrations.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        inventory.overlay(graphics, mouseX, mouseY, drawCursor = false)
    }

    private fun prepareFrame() {
        updateViewport()
        if (!isUiWindowFocused() || !inventory.interactionsEnabled || sendingAction()) cancelInteraction()
        mirrorDrag()
        inventory.refresh()
        layer.prepare(width, height)
        updateSlotCoordinates()
    }

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

    // Compose owns the clipped visual content, while AbstractContainerScreen
    // still owns menu extraction, recipe overlays and interaction state.
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {}

    // Compose slots draw their own hover state; the vanilla highlight would cover them and their icons.
    override fun extractSlotHighlightBack(graphics: GuiGraphicsExtractor) {}

    override fun extractSlotHighlightFront(graphics: GuiGraphicsExtractor) {}

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {}

    override fun extractCarriedItem(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rendered = inventory.renderPoint(mouseX.toDouble(), mouseY.toDouble())
        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(rendered.x - mouseX, rendered.y - mouseY)
            super.extractCarriedItem(graphics, mouseX, mouseY)
        } finally {
            graphics.pose().popMatrix()
        }
    }

    override fun extractTooltip(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rendered = inventory.renderPoint(mouseX.toDouble(), mouseY.toDouble())
        super.extractTooltip(graphics, rendered.x.roundToInt(), rendered.y.roundToInt())
    }

    override fun hasClickedOutside(mouseX: Double, mouseY: Double, left: Int, top: Int): Boolean {
        val bounds = inventory.areaBounds() ?: return super.hasClickedOutside(mouseX, mouseY, left, top)
        return mouseX < bounds.left || mouseX >= bounds.right || mouseY < bounds.top || mouseY >= bounds.bottom
    }

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

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        val button = event.button()
        for (child in children().asReversed()) if (child.mouseClicked(event, doubleClick)) {
            nativeCapture = child
            setFocused(child)
            return true
        }
        setFocused(null)
        val area = inventory.areaBounds()
        val outside = area != null && (x < area.left || x >= area.right || y < area.top || y >= area.bottom)
        nativePress = !sendingAction() && inventory.interactionsEnabled && (inventory.slotAt(x, y) >= 0 || outside)
        dragButton = if (button == 0 || button == 1) button else 2
        val handled = if (nativePress) super.mouseClicked(event, doubleClick) else false
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

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        nativeCapture?.let {
            return it.mouseDragged(event, dx, dy)
        }
        val handled =
            if (nativePress && inventory.interactionsEnabled && !sendingAction()) {
                duringDrag = true
                try {
                    super.mouseDragged(event, dx, dy)
                } finally {
                    duringDrag = false
                }
            } else false
        mirrorDrag()
        inventory.refresh()
        // The hovered slot follows the pointer while a button is held, as it does when the pointer moves.
        inventory.move(event.x(), event.y())
        return layer.handled(layer.move(event.x(), event.y())) || handled
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        nativeCapture?.let {
            nativeCapture = null
            it.mouseReleased(event)
            return true
        }
        val handled =
            if (nativePress && inventory.interactionsEnabled && !sendingAction()) super.mouseReleased(event) else false
        nativePress = false
        mirrorDrag()
        inventory.refresh()
        return layer.handled(layer.release(event.x(), event.y(), event.button())) || handled
    }

    override fun mouseScrolled(x: Double, y: Double, sx: Double, sy: Double): Boolean {
        for (child in children().asReversed()) if (child.isMouseOver(x, y) && child.mouseScrolled(x, y, sx, sy))
            return true
        cancelInteraction()
        // Calling the native hook also lets container integrations consume wheel gestures.
        return super.mouseScrolled(x, y, sx, sy) || layer.handled(layer.scroll(x, y, sx, sy))
    }

    // Every handler reports real consumption, so keys and text that nothing uses reach the Post events.
    override fun keyPressed(event: KeyEvent): Boolean {
        if (focused?.keyPressed(event) == true) return true
        if (
            sendingAction() &&
                !hasTextInputFocus &&
                Minecraft.getInstance()
                    .options
                    .keyInventory
                    .isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.getKey(event))
        ) {
            onClose()
            return true
        }
        if (hasTextInputFocus || !inventory.interactionsEnabled || sendingAction())
            return layer.handled(layer.keyPressed(event)) || closeOnEscape(event)
        return super.keyPressed(event) || layer.handled(layer.keyPressed(event))
    }

    override fun keyReleased(event: KeyEvent): Boolean =
        super.keyReleased(event) || layer.handled(layer.keyReleased(event))

    override fun charTyped(event: CharacterEvent): Boolean =
        focused?.charTyped(event) == true || layer.handled(layer.charTyped(event))

    // IME support (26.x only; see MinecraftTextInput): a focused widget manages Minecraft's text input and
    // Compose reclaims it afterwards, while input method composition goes where typed text goes.
    override fun setFocused(listener: GuiEventListener?) {
        super.setFocused(listener)
        layer.nativeFocusChanged(listener != null)
    }

    override fun preeditUpdated(event: PreeditEvent?): Boolean =
        focused?.preeditUpdated(event) == true || layer.handled(layer.preedit(event))

    /** Where native key handling is bypassed, an Escape that Compose leaves still closes the screen. */
    private fun closeOnEscape(event: KeyEvent): Boolean {
        if (!event.isEscape() || !shouldCloseOnEsc()) return false
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
