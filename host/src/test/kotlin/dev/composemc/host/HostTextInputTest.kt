package dev.composemc.host

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import dev.composemc.bridge.ComposeThread
import dev.composemc.platform.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class HostTextInputTest {
    private class Recorder : HostTextInput.Target {
        private val calls = mutableListOf<String>()

        override fun setOpen(open: Boolean) {
            calls += if (open) "open" else "close"
        }

        override fun setArea(area: TextInputArea) {
            calls += "area"
        }

        override fun cancelComposing() {
            calls += "cancel"
        }

        fun take(): List<String> = calls.toList().also { calls.clear() }
    }

    /** A session with one text field filling the viewport, and its host-side text input. */
    private class Fixture : AutoCloseable {
        val value = ComposeThread.call { mutableStateOf("") }
        val session =
            UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
        val target = Recorder()
        val input = HostTextInput(target)
        private var time = 0L
        val text: String
            get() = ComposeThread.call { value.value }

        fun frame() {
            time += 1_000_000
            session.frame(time)?.close()
            input.afterFrame(session)
        }

        fun focusField() {
            session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
            session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
            frame()
        }

        override fun close() = session.close()
    }

    @Test
    fun `opens while a text field has focus and follows its caret`() =
        Fixture().use { f ->
            f.frame()
            assertEquals(emptyList(), f.target.take())
            f.focusField()
            assertEquals(listOf("open", "area"), f.target.take())
            assertTrue(f.input.open)
            f.frame()
            assertEquals(emptyList(), f.target.take())
            assertTrue(f.session.commitText("abc"))
            f.frame()
            assertEquals(listOf("area"), f.target.take())
            f.session.setFocused(false)
            f.frame()
            assertEquals(listOf("close"), f.target.take())
            f.input.close()
            assertEquals(emptyList(), f.target.take())
        }

    @Test
    fun `a focused native widget manages the text input until it lets go`() =
        Fixture().use { f ->
            f.focusField()
            f.target.take()
            f.input.nativeFocusChanged(f.session, true)
            f.frame()
            assertEquals(emptyList(), f.target.take(), "Compose kept driving the text input of a native widget")
            f.input.nativeFocusChanged(f.session, false)
            assertEquals(listOf("open"), f.target.take(), "The field did not reclaim the text input")
            f.frame()
            assertEquals(listOf("area"), f.target.take())
            f.input.nativeFocusChanged(f.session, false)
            assertEquals(emptyList(), f.target.take())
        }

    @Test
    fun `a press or native focus discards an unconfirmed composition`() =
        Fixture().use { f ->
            f.focusField()
            f.target.take()
            assertTrue(f.input.setComposingText(f.session, ComposingText("ni")))
            assertEquals("ni", f.text)
            f.input.discard(f.session)
            assertEquals(listOf("cancel"), f.target.take())
            assertEquals("", f.text)
            f.input.discard(f.session)
            assertFalse(f.input.setComposingText(f.session, null))
            assertTrue(f.input.setComposingText(f.session, ComposingText("hao")))
            f.input.nativeFocusChanged(f.session, true)
            assertEquals(listOf("cancel"), f.target.take())
            assertEquals("", f.text)
        }

    @Test
    fun `a composition the input method ends is not cancelled again`() =
        Fixture().use { f ->
            f.focusField()
            f.target.take()
            assertTrue(f.input.setComposingText(f.session, ComposingText("ni")))
            assertTrue(f.session.commitText("你"))
            assertFalse(f.input.setComposingText(f.session, null), "The commit already ended the composition")
            f.input.discard(f.session)
            assertEquals(emptyList(), f.target.take())
            assertEquals("你", f.text)
        }

    @Test
    fun `closing releases held text input`() =
        Fixture().use { f ->
            f.focusField()
            f.target.take()
            f.input.close()
            assertEquals(listOf("close"), f.target.take())
            assertFalse(f.input.open)
        }
}
