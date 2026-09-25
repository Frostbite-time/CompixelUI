package dev.composemc.neoforge

import dev.composemc.host.HostTextInput
import dev.composemc.host.UiSession
import dev.composemc.platform.TextInputArea
import net.minecraft.client.Minecraft
import net.minecraft.client.input.PreeditEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.ceil
import kotlin.math.floor

/**
 * IME support, which only the 26.x adapters have: 1.20.1 and 1.21.1 offer neither Minecraft's text
 * input nor preedit events. Connects a ComposeLayer to Minecraft's TextInputManager through the
 * shared [HostTextInput]. Text input opens while a Compose text field has focus, and a focused
 * native widget manages it instead. The input method's composition shows inside the field, its
 * candidate window follows the caret, and a press or native focus discards an unconfirmed composition.
 */
internal class MinecraftTextInput : HostTextInput.Target {
    private val input = HostTextInput(this)
    private var metrics: ScreenMetrics? = null
    /** Whether Compose holds Minecraft's text input. */
    val open: Boolean get() = input.open

    fun afterFrame(session: UiSession, metrics: ScreenMetrics) {
        this.metrics = metrics
        input.afterFrame(session)
    }
    fun nativeFocusChanged(session: UiSession?, focused: Boolean) = input.nativeFocusChanged(session, focused)
    fun preedit(session: UiSession?, event: PreeditEvent?): Boolean = input.setComposingText(session, event.toComposingText())
    fun beforePress(session: UiSession?) = input.discard(session)
    fun close() = input.close()

    // GLFW delivers typed characters regardless; open text input keeps the input method enabled.
    override fun setOpen(open: Boolean) = Minecraft.getInstance().textInputManager().onTextInputFocusChange(open)
    override fun setArea(area: TextInputArea) {
        val gui = guiTextInputArea(area, metrics ?: return)
        Minecraft.getInstance().textInputManager().setTextInputArea(gui.left, gui.top, gui.right, gui.bottom)
    }
    override fun cancelComposing() = GLFW.glfwResetPreeditText(Minecraft.getInstance().window.handle())
}

/** A text input area in GUI units, as TextInputManager takes it. */
internal data class GuiTextInputArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** [area], given in framebuffer pixels, in GUI units rounded outwards. */
internal fun guiTextInputArea(area: TextInputArea, metrics: ScreenMetrics) = GuiTextInputArea(
    floor(area.left * metrics.guiWidth / metrics.framebufferWidth).toInt(),
    floor(area.top * metrics.guiHeight / metrics.framebufferHeight).toInt(),
    ceil(area.right * metrics.guiWidth / metrics.framebufferWidth).toInt(),
    ceil(area.bottom * metrics.guiHeight / metrics.framebufferHeight).toInt(),
)
