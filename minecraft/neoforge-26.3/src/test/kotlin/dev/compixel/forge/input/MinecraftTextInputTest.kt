package dev.compixel.forge.input

import dev.compixel.forge.render.ScreenMetrics
import dev.compixel.platform.ComposingText
import dev.compixel.platform.TextInputArea
import kotlin.test.*
import net.minecraft.client.input.PreeditEvent
import org.junit.jupiter.api.Test

class MinecraftTextInputTest {
    @Test
    fun `preedit events become composing text`() {
        assertNull((null as PreeditEvent?).toComposingText())
        assertNull(PreeditEvent("", 0, listOf(""), 0).toComposingText())
        assertEquals(ComposingText("拼音", 1), PreeditEvent("拼音", 1, listOf("拼", "音"), 1).toComposingText())
        // The caret is a UTF-16 index, so it lands after a whole surrogate pair.
        assertEquals(ComposingText("𝄞a", 2), PreeditEvent("𝄞a", 2, listOf("𝄞a"), 0).toComposingText())
    }

    @Test
    fun `text input areas convert to GUI units rounded outwards`() {
        val metrics = ScreenMetrics(1920, 1080, 640, 360, 3f, 1f)
        assertEquals(GuiTextInputArea(10, 20, 14, 27), guiTextInputArea(TextInputArea(31f, 60f, 40f, 79f), metrics))
    }

    @Test
    fun `odd-window candidate rectangles enclose the physical caret without stretching`() {
        val metrics = ScreenMetrics(1927, 1447, 482, 362, 4f, 1f)
        assertEquals(
            GuiTextInputArea(479, 359, 480, 360),
            guiTextInputArea(TextInputArea(1919.5f, 1439.5f, 1920f, 1440f), metrics),
        )
    }
}
