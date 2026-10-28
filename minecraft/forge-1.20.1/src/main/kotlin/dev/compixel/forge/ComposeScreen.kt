package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.item.NativeItemStatistics
import dev.compixel.forge.item.NativeTooltipStatistics
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.host.GameThread
import dev.compixel.host.UiStateBinding
import dev.compixel.render.*
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import kotlin.properties.ReadOnlyProperty
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Full-screen Compose adapter. A subclass draws its [Content] on the Compose thread, reads the game with [gameState],
 * whose read blocks run on the game thread, and changes it with tasks it posts to [gameThread]; it extends
 * `ComposeScreen<Unit, Nothing>`. The snapshot model, with [snapshot], [handle] and [send], is deprecated and removed
 * in 0.2.0. Until then it mixes with game state: [snapshot] reads the game into one immutable value for [Content], and
 * [handle] runs the actions the content sends.
 *
 * Actions and tasks queued while Compose handles an input event run before the event returns, as vanilla widgets act
 * inside their input handlers, and the game state refreshes before it returns; those queued at other times run at the
 * next tick. The screen takes a snapshot and samples its member game state when it opens, before the first composition;
 * then it takes a snapshot every tick and samples each game state at its interval, and both after actions or tasks ran.
 * Actions and tasks still pending when it is removed are discarded, and a screen shown again starts from a new
 * snapshot. When its content uses ScreenTransition, closing gives the player control back at once while the content
 * plays its exit above the game with the last values.
 *
 * Widgets added through screen initialization events draw above the Compose layer and receive input first. All
 * rendering and resource retirement run on the game thread.
 */
abstract class ComposeScreen<S, A>(
    title: Component,
    private val parent: Screen? = null,
    guiUnitsPerDp: Float = 1f,
    val renderBackend: RenderBackend = configuredRenderBackend(),
    nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    minimumUiDensity: Float = 1f,
    design: UiDesign = OreDesign(),
) : Screen(title) {
    /** The content's game state, opened and closed with each Compose session. */
    @Suppress("DEPRECATION") internal val contentState = UiStateBinding<S, A>(::snapshot, ::handle)
    private val layer =
        ComposeLayer(
            renderBackend,
            guiUnitsPerDp,
            nativeItemOptions,
            minimumUiDensity,
            design = design,
            nativeDrawingOptions = nativeDrawingOptions,
            windowFocused = { isUiWindowFocused() },
            contentState = contentState,
            closeHost = ::onClose,
            openColorEditor = { Minecraft.getInstance().setScreen(ColorEditorScreen(this, design)) },
            content = { Content(contentState.value) },
        )
    private var nativeCapture: GuiEventListener? = null
    internal val session
        get() = layer.session

    val nativeDrawingStatistics
        get() = layer.nativeDrawingStatistics

    val nativeItemStatistics: NativeItemStatistics
        get() = layer.nativeItemStatistics

    val nativeTooltipStatistics: NativeTooltipStatistics
        get() = layer.nativeTooltipStatistics

    internal val nativeTooltipBounds
        get() = layer.nativeTooltipBounds

    val rendererStatistics: RendererStatistics
        get() = layer.rendererStatistics

    val hasTextInputFocus: Boolean
        get() = layer.hasTextInputFocus || (focused as? EditBox)?.canConsumeInput() == true

    /** Opt-in per-frame history. Read snapshots on the game/render thread. */
    val frameProfiler: UiFrameProfiler?
        get() = layer.frameProfiler

    /**
     * Reads the game on the game thread for the snapshot model. It can run more than once per tick, so only read.
     * Return an immutable value: an equal snapshot leaves the content as it is. Screens that read the game with
     * [gameState] leave it out.
     */
    @Deprecated("Read the game with gameState; snapshot is removed in 0.2.0")
    @Suppress("UNCHECKED_CAST")
    protected open fun snapshot(): S = Unit as S

    /**
     * Runs an action from the content on the game thread, before the next snapshot: before the input event that sent it
     * returns, otherwise at the next tick. It may close the screen.
     */
    @Deprecated("Change the game in gameThread.post; handle is removed in 0.2.0")
    protected open fun handle(action: A) {}

    /**
     * The content, composed on the Compose thread. Read the game through [gameState] and change it through
     * [gameThread], and otherwise use immutable values prepared during construction: never read game objects such as
     * the player here. [state] is the latest [snapshot].
     */
    @Composable protected abstract fun Content(state: S)

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
     * For example: `onClick = { gameThread.post { machine.enabled = !machine.enabled } }`.
     */
    val gameThread: GameThread
        get() = contentState.gameThread

    /**
     * Game state for a member property, such as `private val enabled by gameState { machine.enabled }`. [read] runs on
     * the game thread when the screen opens, before its first composition, then every [intervalTicks] client ticks and
     * after the content's actions and tasks. The property shows the latest result, recomposes its readers when it
     * changes and never runs [read] itself. Declare it while the screen is constructed, in the screen or in a state
     * object it creates; state that only part of the content needs can use the composable gameState with an initial
     * value. [read] only reads, and returns values or immutable data, never game objects.
     */
    fun <T> gameState(intervalTicks: Int = 1, read: () -> T): ReadOnlyProperty<Any?, T> =
        contentState.gameState(intervalTicks, read)

    override fun init() {
        nativeCapture = null
        CoveredScreens.remove(this)
        ScreenExits.reclaim(layer)
        layer.open(width, height)
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        layer.render(guiGraphics, width, height)
        // Widgets from screen initialization events draw above Compose; the vanilla backdrop is not drawn.
        for (renderable in renderables) renderable.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun tick() {
        layer.tick()
        layer.tickContent()
    }

    // Native widgets take pointer input first, as in inventory hosts; Compose receives the rest.
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(mouseX, mouseY, button)) {
            nativeCapture = child
            setFocused(child)
            return true
        }
        setFocused(null)
        return layer.handled(layer.press(mouseX, mouseY, button))
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        nativeCapture?.let {
            nativeCapture = null
            it.mouseReleased(mouseX, mouseY, button)
            return true
        }
        return layer.handled(layer.release(mouseX, mouseY, button))
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        nativeCapture?.mouseDragged(mouseX, mouseY, button, dragX, dragY) ?: layer.handled(layer.move(mouseX, mouseY))

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        layer.handled(layer.move(mouseX, mouseY))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean {
        for (child in children().asReversed()) if (
            child.isMouseOver(mouseX, mouseY) && child.mouseScrolled(mouseX, mouseY, scrollY)
        )
            return true
        return layer.handled(layer.scroll(mouseX, mouseY, 0.0, scrollY))
    }

    // A focused widget, otherwise Compose, gets keys and text first. What they leave reaches vanilla
    // handling (Escape, focus navigation); an unconsumed event returns false, so its Post event fires.
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focused == null && layer.handled(layer.keyPressed(keyCode, scanCode, modifiers)) ||
            super.keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focused == null && layer.handled(layer.keyReleased(keyCode, scanCode, modifiers)) ||
            super.keyReleased(keyCode, scanCode, modifiers)

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        focused == null && layer.handled(layer.charTyped(codePoint)) || super.charTyped(codePoint, modifiers)

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    override fun removed() {
        nativeCapture = null
        try {
            if (coveredByAnotherScreen()) {
                layer.suspend()
                CoveredScreens.add(this, ::coveredByAnotherScreen, ::closeCovered)
            } else ScreenExits.close(layer)
        } finally {
            super.removed()
        }
    }

    /** Whether the screen that replaced this one only covers it and may show it again, keeping its session. */
    internal open fun coveredByAnotherScreen() = false

    /** A covered screen will not show again: its session ends. */
    internal open fun closeCovered() = layer.close()

    override fun isPauseScreen(): Boolean = false

    /** The ordinary host follows OS focus; an automated fixture can supply its own logical focus. */
    protected open fun isUiWindowFocused(): Boolean = Minecraft.getInstance().isWindowActive
}
