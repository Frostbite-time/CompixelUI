package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.item.NativeItemStatistics
import dev.compixel.forge.item.NativeTooltipStatistics
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.host.UiStateBinding
import dev.compixel.render.*
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import dev.compixel.ui.theme.ThemeId
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Full-screen Compose adapter. A subclass reads the game into an immutable [snapshot], draws the latest one in
 * [Content] and runs the actions its content [send]s in [handle]; `ComposeScreen<Unit, Nothing>` shows content without
 * game state. Actions sent while Compose handles an input event run before the event returns, as vanilla widgets act
 * inside their input handlers; actions sent at other times run at the next tick. The screen takes a snapshot when it
 * opens, after an input event's actions and every tick. Actions still pending when it is removed are discarded, and a
 * screen shown again starts from a new snapshot.
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
    theme: ThemeId = ThemeId.Default,
    design: UiDesign = OreDesign,
) : Screen(title) {
    /** The content's game state, opened and closed with each Compose session. */
    internal val contentState = UiStateBinding<S, A>(::snapshot, ::handle)
    private val layer =
        ComposeLayer(
            renderBackend,
            guiUnitsPerDp,
            nativeItemOptions,
            minimumUiDensity,
            theme = theme,
            design = design,
            nativeDrawingOptions = nativeDrawingOptions,
            windowFocused = { isUiWindowFocused() },
            contentState = contentState,
            content = { Content(contentState.value) },
        )
    private val closeRequested = AtomicBoolean()
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
     * Reads the game on the game thread. It can run more than once per tick, so only read. Return an immutable value:
     * an equal snapshot leaves the content as it is.
     */
    protected abstract fun snapshot(): S

    /**
     * Runs an action from the content on the game thread, before the next snapshot: before the input event that sent it
     * returns, otherwise at the next tick. It may close the screen.
     */
    protected abstract fun handle(action: A)

    /**
     * The content for the latest [state], composed on the Compose thread. Use [state], [send] and immutable values
     * prepared during construction, never game objects such as the player.
     */
    @Composable protected abstract fun Content(state: S)

    /** Queues [action] for [handle] from any thread. False while the screen is not shown or 64 actions are waiting. */
    protected fun send(action: A): Boolean = contentState.send(action)

    /**
     * Closes the screen on the game thread, as [onClose] does: before the input event during which it was called
     * returns, otherwise at the next tick. Call it from any thread, for example from a close button in the content.
     */
    protected fun requestClose() = closeRequested.set(true)

    override fun init() {
        nativeCapture = null
        layer.open(width, height)
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        layer.render(guiGraphics, width, height)
        // Widgets from screen initialization events draw above Compose; the vanilla backdrop is not drawn.
        for (renderable in renderables) renderable.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun tick() {
        layer.tick()
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

    // Native widgets take pointer input first, as in inventory hosts; Compose receives the rest.
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(mouseX, mouseY, button)) {
            nativeCapture = child
            setFocused(child)
            return true
        }
        setFocused(null)
        return contentHandled(layer.press(mouseX, mouseY, button))
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        nativeCapture?.let {
            nativeCapture = null
            it.mouseReleased(mouseX, mouseY, button)
            return true
        }
        return contentHandled(layer.release(mouseX, mouseY, button))
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        nativeCapture?.mouseDragged(mouseX, mouseY, button, dragX, dragY) ?: contentHandled(layer.move(mouseX, mouseY))

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        contentHandled(layer.move(mouseX, mouseY))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean {
        for (child in children().asReversed()) if (
            child.isMouseOver(mouseX, mouseY) && child.mouseScrolled(mouseX, mouseY, scrollY)
        )
            return true
        return contentHandled(layer.scroll(mouseX, mouseY, 0.0, scrollY))
    }

    // A focused widget, otherwise Compose, gets keys and text first. What they leave reaches vanilla
    // handling (Escape, focus navigation); an unconsumed event returns false, so its Post event fires.
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focused == null && contentHandled(layer.keyPressed(keyCode, scanCode, modifiers)) ||
            super.keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focused == null && contentHandled(layer.keyReleased(keyCode, scanCode, modifiers)) ||
            super.keyReleased(keyCode, scanCode, modifiers)

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        focused == null && contentHandled(layer.charTyped(codePoint)) || super.charTyped(codePoint, modifiers)

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    override fun removed() {
        nativeCapture = null
        closeRequested.set(false) // A request ends with the session that made it.
        try {
            layer.close()
        } finally {
            super.removed()
        }
    }

    override fun isPauseScreen(): Boolean = false

    /** The ordinary host follows OS focus; an automated fixture can supply its own logical focus. */
    protected open fun isUiWindowFocused(): Boolean = Minecraft.getInstance().isWindowActive
}
