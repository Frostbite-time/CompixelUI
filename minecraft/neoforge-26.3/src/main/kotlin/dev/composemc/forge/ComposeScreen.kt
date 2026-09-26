package dev.composemc.forge

import androidx.compose.runtime.Composable
import dev.composemc.render.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent // IME
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import dev.composemc.forge.input.MinecraftTextInput
import dev.composemc.forge.item.NativeItemOptions
import dev.composemc.forge.item.NativeItemStatistics
import dev.composemc.forge.item.NativeTooltipStatistics
import dev.composemc.forge.render.configuredRenderBackend

/**
 * Full-screen Compose adapter. Widgets added through screen initialization events draw above the
 * Compose layer and receive input first. All rendering and resource retirement run on the game thread.
 */
open class ComposeScreen(
    title: Component,
    private val parent: Screen? = null,
    guiUnitsPerDp: Float = 1f,
    val renderBackend: RenderBackend = configuredRenderBackend(),
    nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    minimumUiDensity: Float = 1f,
    content: @Composable () -> Unit,
) : Screen(title) {
    private val layer = ComposeLayer(renderBackend, guiUnitsPerDp, nativeItemOptions, minimumUiDensity,
        windowFocused = { isUiWindowFocused() }, content = content)
    private var nativeCapture: GuiEventListener? = null
    internal val session get() = layer.session
    val nativeItemStatistics: NativeItemStatistics get() = layer.nativeItemStatistics
    val nativeTooltipStatistics: NativeTooltipStatistics get() = layer.nativeTooltipStatistics
    internal val nativeTooltipBounds get() = layer.nativeTooltipBounds
    val rendererStatistics: RendererStatistics get() = layer.rendererStatistics
    val hasTextInputFocus: Boolean get() = layer.hasTextInputFocus || (focused as? EditBox)?.canConsumeInput() == true
    internal val textInputOpen: Boolean get() = layer.textInputOpen
    /** Opt-in per-frame history. Read snapshots on the game/render thread. */
    val frameProfiler: UiFrameProfiler? get() = layer.frameProfiler

    override fun init() { nativeCapture = null; layer.open(width, height) }

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

    override fun tick() = layer.tick()

    // Native widgets take pointer input first, as in inventory hosts; Compose receives the rest.
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(event, doubleClick)) { nativeCapture = child; setFocused(child); return true }
        setFocused(null)
        return layer.press(event.x(), event.y(), event.button())
    }
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        nativeCapture?.let { nativeCapture = null; it.mouseReleased(event); return true }
        return layer.release(event.x(), event.y(), event.button())
    }
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        nativeCapture?.mouseDragged(event, dragX, dragY) ?: layer.move(event.x(), event.y())
    override fun mouseMoved(mouseX: Double, mouseY: Double) { layer.move(mouseX, mouseY) }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        for (child in children().asReversed()) if (child.isMouseOver(mouseX, mouseY) && child.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true
        return layer.scroll(mouseX, mouseY, scrollX, scrollY)
    }

    // A focused widget, otherwise Compose, gets keys and text first. What they leave reaches vanilla
    // handling (Escape, focus navigation); an unconsumed event returns false, so its Post event fires.
    override fun keyPressed(event: KeyEvent): Boolean = focused == null && layer.keyPressed(event) || super.keyPressed(event)
    override fun keyReleased(event: KeyEvent): Boolean = focused == null && layer.keyReleased(event) || super.keyReleased(event)
    override fun charTyped(event: CharacterEvent): Boolean = focused == null && layer.charTyped(event) || super.charTyped(event)
    // IME support (26.x only; see MinecraftTextInput): a focused widget manages Minecraft's text input and
    // Compose reclaims it afterwards, while input method composition goes where typed text goes.
    override fun setFocused(listener: GuiEventListener?) {
        super.setFocused(listener)
        layer.nativeFocusChanged(listener != null)
    }
    override fun preeditUpdated(event: PreeditEvent?): Boolean = focused == null && layer.preedit(event) || super.preeditUpdated(event)

    override fun onClose() {
        // Gui owns both the parent-screen and return-to-game transitions.
        Minecraft.getInstance().gui.setScreen(parent)
    }
    override fun removed() {
        nativeCapture = null
        try { layer.close() } finally { super.removed() }
    }
    override fun isPauseScreen(): Boolean = false

    /** The ordinary host follows OS focus; an automated fixture can supply its own logical focus. */
    protected open fun isUiWindowFocused(): Boolean = Minecraft.getInstance().isWindowActive
}
