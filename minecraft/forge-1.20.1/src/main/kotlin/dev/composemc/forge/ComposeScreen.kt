package dev.composemc.forge

import androidx.compose.runtime.Composable
import dev.composemc.forge.item.NativeItemOptions
import dev.composemc.forge.item.NativeItemStatistics
import dev.composemc.forge.item.NativeTooltipStatistics
import dev.composemc.forge.render.configuredRenderBackend
import dev.composemc.render.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Full-screen Compose adapter. Widgets added through screen initialization events draw above the Compose layer and
 * receive input first. All rendering and resource retirement run on the game thread.
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
    private val layer =
        ComposeLayer(
            renderBackend,
            guiUnitsPerDp,
            nativeItemOptions,
            minimumUiDensity,
            windowFocused = { isUiWindowFocused() },
            content = content,
        )
    private var nativeCapture: GuiEventListener? = null
    internal val session
        get() = layer.session

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

    override fun init() {
        nativeCapture = null
        layer.open(width, height)
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        layer.render(guiGraphics, width, height)
        // Widgets from screen initialization events draw above Compose; the vanilla backdrop is not drawn.
        for (renderable in renderables) renderable.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun tick() = layer.tick()

    // Native widgets take pointer input first, as in inventory hosts; Compose receives the rest.
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        for (child in children().asReversed()) if (child.mouseClicked(mouseX, mouseY, button)) {
            nativeCapture = child
            setFocused(child)
            return true
        }
        setFocused(null)
        return layer.press(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        nativeCapture?.let {
            nativeCapture = null
            it.mouseReleased(mouseX, mouseY, button)
            return true
        }
        return layer.release(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        nativeCapture?.mouseDragged(mouseX, mouseY, button, dragX, dragY) ?: layer.move(mouseX, mouseY)

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        layer.move(mouseX, mouseY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean {
        for (child in children().asReversed()) if (
            child.isMouseOver(mouseX, mouseY) && child.mouseScrolled(mouseX, mouseY, scrollY)
        )
            return true
        return layer.scroll(mouseX, mouseY, 0.0, scrollY)
    }

    // A focused widget, otherwise Compose, gets keys and text first. What they leave reaches vanilla
    // handling (Escape, focus navigation); an unconsumed event returns false, so its Post event fires.
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focused == null && layer.keyPressed(keyCode, scanCode, modifiers) ||
            super.keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focused == null && layer.keyReleased(keyCode, scanCode, modifiers) ||
            super.keyReleased(keyCode, scanCode, modifiers)

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        focused == null && layer.charTyped(codePoint) || super.charTyped(codePoint, modifiers)

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    override fun removed() {
        nativeCapture = null
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
