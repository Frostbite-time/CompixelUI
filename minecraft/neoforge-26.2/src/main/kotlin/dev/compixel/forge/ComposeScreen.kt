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
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent // IME
import net.minecraft.network.chat.Component

/**
 * Full-screen Compose adapter. A subclass reads the game into an immutable [snapshot], draws the latest one in
 * [Content] and runs the actions its content [send]s in [handle]; `ComposeScreen<Unit, Nothing>` shows content without
 * game state. Actions sent while Compose handles an input event run before the event returns, as vanilla widgets act
 * inside their input handlers; actions sent at other times run at the next tick. The screen takes a snapshot when it
 * opens, after an input event's actions and every tick. Actions still pending when it is removed are discarded, and a
 * screen shown again starts from a new snapshot. When its content uses ScreenTransition, closing gives the player
 * control back at once while the content plays its exit above the game.
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
            closeHost = ::onClose,
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

    internal val textInputOpen: Boolean
        get() = layer.textInputOpen

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
    protected fun requestClose() = layer.requestClose()

    override fun init() {
        nativeCapture = null
        CoveredScreens.remove(this)
        ScreenExits.reclaim(layer)
        layer.open(width, height)
    }

    // Compose supplies the backdrop, as on 1.20.1/1.21.1: skip the vanilla panorama, blur and
    // menu texture, but keep the deferred subtitle pass that vanilla runs here.
    override fun extractBackground(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Minecraft.getInstance().gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        layer.render(guiGraphics, width, height)
        // Widgets from screen initialization events draw above Compose.
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun tick() {
        layer.tick()
        layer.tickContent()
    }

    // Native widgets take pointer input first, as in inventory hosts; Compose receives the rest.
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(event, doubleClick)) {
            nativeCapture = child
            setFocused(child)
            return true
        }
        setFocused(null)
        return layer.handled(layer.press(event.x(), event.y(), event.button()))
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        nativeCapture?.let {
            nativeCapture = null
            it.mouseReleased(event)
            return true
        }
        return layer.handled(layer.release(event.x(), event.y(), event.button()))
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        nativeCapture?.mouseDragged(event, dragX, dragY) ?: layer.handled(layer.move(event.x(), event.y()))

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
    override fun keyPressed(event: KeyEvent): Boolean =
        focused == null && layer.handled(layer.keyPressed(event)) || super.keyPressed(event)

    override fun keyReleased(event: KeyEvent): Boolean =
        focused == null && layer.handled(layer.keyReleased(event)) || super.keyReleased(event)

    override fun charTyped(event: CharacterEvent): Boolean =
        focused == null && layer.handled(layer.charTyped(event)) || super.charTyped(event)

    // IME support (26.x only; see MinecraftTextInput): a focused widget manages Minecraft's text input and
    // Compose reclaims it afterwards, while input method composition goes where typed text goes.
    override fun setFocused(listener: GuiEventListener?) {
        super.setFocused(listener)
        layer.nativeFocusChanged(listener != null)
    }

    override fun preeditUpdated(event: PreeditEvent?): Boolean =
        focused == null && layer.handled(layer.preedit(event)) || super.preeditUpdated(event)

    override fun onClose() {
        // Gui owns both the parent-screen and return-to-game transitions.
        Minecraft.getInstance().gui.setScreen(parent)
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
