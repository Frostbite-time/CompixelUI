package dev.compixel.host

import androidx.compose.runtime.SideEffect
import dev.compixel.bridge.ComposeThread
import dev.compixel.platform.Viewport
import kotlin.test.*
import org.junit.jupiter.api.Test

class UiBindingTest {
    @Test
    fun `snapshots recompose only when changed`() {
        val binding = UiBinding<String, Int>("initial")
        var observed = ""
        var compositions = 0
        UiSession(Viewport(20, 20)) {
                val value = binding.value
                SideEffect {
                    observed = value
                    compositions++
                }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals("initial", observed)
                val initialCount = compositions
                binding.update("initial")
                session.frame(2)?.close()
                assertEquals(initialCount, compositions)
                binding.update("changed")
                session.frame(3)?.close()
                assertEquals("changed", observed)
            }
        binding.close()
    }

    @Test
    fun `UI actions run in order on the owner and queue stays bounded`() {
        val owner = Thread.currentThread()
        val binding = UiBinding<Unit, Int>(Unit, 2)
        ComposeThread.call {
            assertTrue(binding.send(1))
            assertTrue(binding.send(2))
            assertFalse(binding.send(3))
        }
        val received = mutableListOf<Int>()
        binding.drainActions {
            assertSame(owner, Thread.currentThread())
            received += it
            if (it == 1) assertTrue(binding.send(4))
        }
        assertEquals(listOf(1, 2), received)
        binding.drainActions { received += it }
        assertEquals(listOf(1, 2, 4), received)
        binding.close()
    }

    @Test
    fun `close discards pending and late actions`() {
        val binding = UiBinding<Unit, Int>(Unit)
        binding.send(1)
        binding.close()
        binding.close()
        assertFalse(ComposeThread.call { binding.send(2) })
        binding.drainActions { fail("Closed action executed") }
        assertFailsWith<IllegalStateException> { binding.update(Unit) }
    }

    @Test
    fun `owner operations cannot execute from the UI thread`() {
        val binding = UiBinding<Int, Int>(0)
        ComposeThread.call {
            assertFailsWith<IllegalStateException> { binding.update(1) }
            assertFailsWith<IllegalStateException> { binding.drainActions {} }
            assertFailsWith<IllegalStateException> { binding.close() }
        }
        binding.close()
    }
}
