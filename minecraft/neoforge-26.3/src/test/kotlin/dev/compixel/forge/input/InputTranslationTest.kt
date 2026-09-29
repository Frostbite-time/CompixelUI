package dev.compixel.forge.input

import com.mojang.blaze3d.platform.InputConstants
import dev.compixel.platform.UiKey
import kotlin.test.*
import net.minecraft.client.input.KeyEvent
import org.junit.jupiter.api.Test

class InputTranslationTest {
    /** A keycode that types no character, as SDL reports for keys such as F5. */
    private val noCharacter = 1 shl 30

    @Test
    fun `every key has an SDL position`() {
        assertEquals(UiKey.entries.toSet(), (0..512).map(::positionKey).toSet())
    }

    @Test
    fun `letters and punctuation follow the active layout`() {
        assertEquals(UiKey.A, KeyEvent(InputConstants.KEY_Q, 'a'.code, 0).toUiKey()) // AZERTY
        assertEquals(UiKey.M, uiKey(InputConstants.KEY_SEMICOLON, 'm'.code))
        assertEquals(UiKey.COMMA, uiKey(InputConstants.KEY_M, ','.code))
        assertEquals(UiKey.Z, uiKey(InputConstants.KEY_Y, 'z'.code)) // QWERTZ
    }

    @Test
    fun `other characters and keys keep their scancode position`() {
        assertEquals(UiKey.A, uiKey(InputConstants.KEY_A, 'ф'.code)) // Cyrillic keeps the Latin position
        assertEquals(UiKey.SEMICOLON, uiKey(InputConstants.KEY_SEMICOLON, 'é'.code))
        assertEquals(UiKey.DIGIT_1, uiKey(InputConstants.KEY_1, '&'.code)) // AZERTY number row
        assertEquals(UiKey.DIGIT_0, uiKey(InputConstants.KEY_0, 'à'.code))
        assertEquals(UiKey.NUMPAD_0, uiKey(InputConstants.KEY_NUMPAD0, '0'.code))
        assertEquals(UiKey.F5, uiKey(InputConstants.KEY_F5, noCharacter))
        assertEquals(UiKey.NUMPAD_ENTER, uiKey(InputConstants.KEY_NUMPADENTER, noCharacter))
        assertEquals(UiKey.CTRL_RIGHT, uiKey(InputConstants.KEY_RCONTROL, noCharacter))
    }
}
