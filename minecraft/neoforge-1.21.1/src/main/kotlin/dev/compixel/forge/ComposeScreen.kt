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
import dev.compixel.host.gameState
import dev.compixel.render.*
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Full-screen Compose adapter. A subclass draws its [Content], reads the game with [gameState], whose read blocks run
 * on the game thread, and changes it with tasks it posts to [gameThread].
 *
 * Tasks queued while Compose handles an input event run before the event returns, as vanilla widgets act inside their
 * input handlers, and the game state refreshes before it returns; those queued at other times run at the next tick. The
 * content reads each game state immediately when it first composes, then at its interval and after tasks ran. Tasks
 * still pending when it is removed are discarded, and a screen shown again samples its game state anew. When its
 * content uses ScreenTransition, closing gives the player control back at once while the content plays its exit above
 * the game with the last values.
 *
 * Widgets added through screen initialization events draw above the Compose layer and receive input first. All
 * rendering and resource retirement run on the game thread.
 */
abstract class ComposeScreen(
    title: Component,
    private val parent: Screen? = null,
    val renderBackend: RenderBackend = configuredRenderBackend(),
    nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    minimumUiDensity: Float = 1f,
    design: UiDesign = OreDesign(),
) : Screen(title) {
    /** The content's game state, opened and closed with each Compose session. */
    internal val contentState = UiStateBinding()
    private val layer =
        ComposeLayer(
            renderBackend,
            ::guiUnitsPerDp,
            nativeItemOptions,
            minimumUiDensity,
            design = design,
            nativeDrawingOptions = nativeDrawingOptions,
            windowFocused = { isUiWindowFocused() },
            contentState = contentState,
            closeHost = ::onClose,
            openColorEditor = { Minecraft.getInstance().setScreen(ColorEditorScreen(this, design)) },
            content = { Content() },
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
     * The content, composed again only when state it reads changes. Read the game through [gameState] and change it
     * through [gameThread], and otherwise use immutable values prepared during construction: never read game objects
     * such as the player here.
     */
    @Composable protected abstract fun Content()

    /**
     * Closes the screen on the game thread, as [onClose] does: before the input event during which it was called
     * returns, otherwise at the next tick. Call it on the game thread, for example from a close button in the content.
     */
    protected fun requestClose() = layer.requestClose()

    /**
     * Runs the content's tasks on the game thread: before the input event that queued them returns, otherwise at the
     * next tick, and the game state refreshes after them. False while the screen is not shown or 64 tasks are waiting.
     *
     * For example: `onClick = { gameThread.post { machine.enabled = !machine.enabled } }`.
     */
    val gameThread: GameThread
        get() = contentState.gameThread

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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        for (child in children().asReversed()) if (
            child.isMouseOver(mouseX, mouseY) && child.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        )
            return true
        return layer.handled(layer.scroll(mouseX, mouseY, scrollX, scrollY))
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

    /**
     * GUI units per Compose dp for the current window framebuffer size and Minecraft [guiScale].
     *
     * Defaults to `1f` (matching vanilla Minecraft's GUI scale). Must return a finite positive value. Override to adapt
     * scale dynamically, for example stepping down one integer scale while keeping density at least `1`: `if
     * (guiScale > 1) (guiScale - 1f) / guiScale else 1f`.
     */
    protected open fun guiUnitsPerDp(framebufferWidth: Int, framebufferHeight: Int, guiScale: Int): Float = 1f
}
