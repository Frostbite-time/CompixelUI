package dev.compixel.forge.input

import dev.compixel.forge.render.ScreenMetrics
import dev.compixel.host.HostTextInput
import dev.compixel.host.UiSession
import dev.compixel.platform.TextInputArea
import kotlin.math.ceil
import kotlin.math.floor
import net.minecraft.client.Minecraft
import net.minecraft.client.input.PreeditEvent
import org.lwjgl.sdl.SDLKeyboard

/**
 * IME support, which only the 26.x adapters have: 1.20.1 and 1.21.1 offer neither Minecraft's text input nor preedit
 * events. Connects a ComposeLayer to Minecraft's TextInputManager through the shared [HostTextInput]. Text input opens
 * while a Compose text field has focus, and a focused native widget manages it instead. The input method's composition
 * shows inside the field, its candidate window follows the caret, and a press or native focus discards an unconfirmed
 * composition.
 */
internal class MinecraftTextInput : HostTextInput.Target {
    private val input = HostTextInput(this)
    private var metrics: ScreenMetrics? = null
    /** Whether Compose holds Minecraft's text input. */
    val open: Boolean
        get() = input.open

    fun afterFrame(session: UiSession, metrics: ScreenMetrics) {
        this.metrics = metrics
        input.afterFrame(session)
    }

    fun nativeFocusChanged(session: UiSession?, focused: Boolean) = input.nativeFocusChanged(session, focused)

    fun preedit(session: UiSession?, event: PreeditEvent?): Boolean =
        input.setComposingText(session, event.toComposingText())

    fun beforePress(session: UiSession?) = input.discard(session)

    fun close() = input.close()

    // SDL delivers typed text only while text input is open; TextInputManager records who opened it.
    override fun setOpen(open: Boolean) = Minecraft.getInstance().textInputManager().onTextInputFocusChange(this, open)

    override fun setArea(area: TextInputArea) {
        val gui = guiTextInputArea(area, metrics ?: return)
        Minecraft.getInstance().textInputManager().setTextInputArea(gui.left, gui.top, gui.right, gui.bottom)
    }

    override fun cancelComposing() {
        SDLKeyboard.SDL_ClearComposition(Minecraft.getInstance().window.handle())
    }
}

/** A text input area in GUI units, as TextInputManager takes it. */
internal data class GuiTextInputArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** [area], given in framebuffer pixels, in GUI units rounded outwards. */
internal fun guiTextInputArea(area: TextInputArea, metrics: ScreenMetrics) =
    GuiTextInputArea(
        // TextInputManager multiplies these coordinates by guiScale; it does not use mouse scaling.
        floor(metrics.renderCoordinate(area.left)).toInt(),
        floor(metrics.renderCoordinate(area.top)).toInt(),
        ceil(metrics.renderCoordinate(area.right)).toInt(),
        ceil(metrics.renderCoordinate(area.bottom)).toInt(),
    )
