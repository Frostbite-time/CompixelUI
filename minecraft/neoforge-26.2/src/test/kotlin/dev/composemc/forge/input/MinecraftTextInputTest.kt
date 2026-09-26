package dev.composemc.forge.input

import dev.composemc.platform.ComposingText
import dev.composemc.platform.TextInputArea
import net.minecraft.client.input.PreeditEvent
import org.junit.jupiter.api.Test
import kotlin.test.*
import dev.composemc.forge.render.ScreenMetrics

class MinecraftTextInputTest {
    @Test fun `preedit events become composing text`() {
        assertNull((null as PreeditEvent?).toComposingText())
        assertNull(PreeditEvent("", 0, listOf(""), 0).toComposingText())
        assertEquals(ComposingText("拼音", 1), PreeditEvent("拼音", 1, listOf("拼", "音"), 1).toComposingText())
        // The caret is a UTF-16 index, so it lands after a whole surrogate pair.
        assertEquals(ComposingText("𝄞a", 2), PreeditEvent("𝄞a", 2, listOf("𝄞a"), 0).toComposingText())
    }

    @Test fun `text input areas convert to GUI units rounded outwards`() {
        val metrics = ScreenMetrics(1920, 1080, 640, 360, 3f, 1f)
        assertEquals(GuiTextInputArea(10, 20, 14, 27), guiTextInputArea(TextInputArea(31f, 60f, 40f, 79f), metrics))
    }
}
