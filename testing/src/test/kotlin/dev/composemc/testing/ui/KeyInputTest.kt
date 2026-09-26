package dev.composemc.testing.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import dev.composemc.bridge.ComposeThread
import dev.composemc.host.UiSession
import dev.composemc.platform.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class KeyInputTest {
    @Test
    fun `shortcut, function, digit, keypad and modifier keys reach Compose`() {
        val received = mutableListOf<String>()
        val focus = FocusRequester()
        var time = 1_000_000_000L
        UiSession(Viewport(200, 200)) {
                Box(
                    Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown)
                            received += "${event.key}${if (event.isCtrlPressed) "+ctrl" else ""}"
                        true
                    }
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            .use { session ->
                fun frame() {
                    time += 16_666_667
                    session.frame(time)?.close()
                }
                frame()
                frame()
                for ((key, modifiers) in
                    listOf(
                        UiKey.S to Modifiers(control = true),
                        UiKey.F5 to Modifiers(),
                        UiKey.DIGIT_1 to Modifiers(),
                        UiKey.NUMPAD_ENTER to Modifiers(),
                        UiKey.SHIFT_LEFT to Modifiers(shift = true),
                    )) {
                    assertTrue(session.key(KeyInput(key, true, modifiers)), "$key was not delivered")
                    session.key(KeyInput(key, false, modifiers))
                    frame()
                }
            }
        val expected = listOf(Key.S, Key.F5, Key.One, Key.NumPadEnter, Key.ShiftLeft).map { it.toString() }
        assertEquals(listOf("${expected[0]}+ctrl") + expected.drop(1), ComposeThread.call { received.toList() })
    }
}
