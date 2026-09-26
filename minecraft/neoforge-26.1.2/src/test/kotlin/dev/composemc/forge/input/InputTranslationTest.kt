package dev.composemc.forge.input

import dev.composemc.platform.UiKey
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

class InputTranslationTest {
    private val notAsked: () -> String? = { fail("Only letter and punctuation positions consult the layout") }

    @Test
    fun `every key has a GLFW position`() {
        assertEquals(UiKey.entries.toSet(), (0..GLFW.GLFW_KEY_LAST).map(::positionKey).toSet())
    }

    @Test
    fun `letters and punctuation follow the active layout`() {
        assertEquals(UiKey.A, uiKey(GLFW.GLFW_KEY_Q) { "a" }) // AZERTY
        assertEquals(UiKey.M, uiKey(GLFW.GLFW_KEY_SEMICOLON) { "m" })
        assertEquals(UiKey.COMMA, uiKey(GLFW.GLFW_KEY_M) { "," })
        assertEquals(UiKey.Z, uiKey(GLFW.GLFW_KEY_Y) { "z" }) // QWERTZ
        assertEquals(UiKey.S, uiKey(GLFW.GLFW_KEY_S) { "S" })
    }

    @Test
    fun `other characters and keys keep their US position`() {
        assertEquals(UiKey.A, uiKey(GLFW.GLFW_KEY_A) { "ф" }) // Cyrillic keeps the Latin position
        assertEquals(UiKey.SEMICOLON, uiKey(GLFW.GLFW_KEY_SEMICOLON) { "é" })
        assertEquals(UiKey.S, uiKey(GLFW.GLFW_KEY_S) { null })
        assertEquals(UiKey.DIGIT_1, uiKey(GLFW.GLFW_KEY_1, notAsked)) // AZERTY types '&' here
        assertEquals(UiKey.F5, uiKey(GLFW.GLFW_KEY_F5, notAsked))
        assertEquals(UiKey.NUMPAD_ENTER, uiKey(GLFW.GLFW_KEY_KP_ENTER, notAsked))
        assertEquals(UiKey.CTRL_RIGHT, uiKey(GLFW.GLFW_KEY_RIGHT_CONTROL, notAsked))
        assertEquals(UiKey.UNKNOWN, uiKey(GLFW.GLFW_KEY_WORLD_1, notAsked))
    }
}
