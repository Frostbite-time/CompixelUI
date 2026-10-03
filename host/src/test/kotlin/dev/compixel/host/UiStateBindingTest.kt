package dev.compixel.host

import androidx.compose.runtime.SideEffect
import dev.compixel.bridge.ComposeThread
import dev.compixel.platform.Viewport
import kotlin.test.*
import org.junit.jupiter.api.Test

class UiStateBindingTest {
    @Test
    fun `content sees the opening snapshot and each tick handles actions before the next`() {
        var energy = 1
        val handled = mutableListOf<Int>()
        val state =
            UiStateBinding<Int, Int>({ energy }) {
                handled += it
                energy += it
            }
        state.open()
        var observed = 0
        UiSession(Viewport(20, 20)) {
                val value = state.value
                SideEffect { observed = value }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals(1, observed)
                assertTrue(ComposeThread.call { state.send(10) })
                state.tick()
                session.frame(2)?.close()
                assertEquals(listOf(10), handled)
                assertEquals(11, observed)
            }
        state.close()
    }

    @Test
    fun `an open session takes one snapshot per tick`() {
        var snapshots = 0
        val state = UiStateBinding<Int, Unit>({ ++snapshots }) {}
        state.open()
        state.open()
        assertEquals(1, snapshots)
        state.tick()
        assertEquals(2, snapshots)
        state.close()
        state.tick()
        assertEquals(2, snapshots)
    }

    @Test
    fun `a handler that ends the session skips the remaining actions and the snapshot`() {
        var snapshots = 0
        val handled = mutableListOf<Int>()
        lateinit var state: UiStateBinding<Int, Int>
        state =
            UiStateBinding({ ++snapshots }) {
                handled += it
                state.close()
            }
        state.open()
        assertTrue(state.send(1))
        assertTrue(state.send(2))
        state.tick()
        assertEquals(listOf(1), handled)
        assertEquals(1, snapshots)
        assertFalse(state.isOpen)
        assertFalse(state.send(3))
    }

    @Test
    fun `a session reopened by the handler keeps its own snapshot`() {
        var snapshots = 0
        lateinit var state: UiStateBinding<Int, Unit>
        state =
            UiStateBinding({ ++snapshots }) {
                state.close()
                state.open()
            }
        state.open()
        assertTrue(state.send(Unit))
        state.tick()
        assertEquals(2, snapshots)
        assertEquals(2, state.composed())
        state.close()
    }

    @Test
    fun `closing discards pending actions and reopening starts from a new snapshot`() {
        var energy = 1
        val handled = mutableListOf<Int>()
        val state = UiStateBinding<Int, Int>({ energy }) { handled += it }
        assertFalse(state.send(0), "An action was accepted before the session opened")
        state.open()
        assertTrue(state.send(1))
        state.close()
        assertFalse(state.send(2), "An action was accepted after the session closed")
        state.tick()
        assertEquals(emptyList(), handled)
        energy = 5
        state.open()
        assertEquals(5, state.composed())
        assertTrue(state.send(3))
        state.tick()
        assertEquals(listOf(3), handled)
        state.close()
    }

    @Test
    fun `ticks and close stay on the opening thread`() {
        val state = UiStateBinding<Int, Int>({ 0 }) {}
        state.open()
        ComposeThread.call {
            assertTrue(state.send(1))
            assertFailsWith<IllegalStateException> { state.tick() }
            assertFailsWith<IllegalStateException> { state.close() }
        }
        assertTrue(state.isOpen)
        state.close()
    }

    private fun <S> UiStateBinding<S, *>.composed(): S {
        val values = mutableListOf<S>()
        UiSession(Viewport(20, 20)) {
                val current = this@composed.value
                SideEffect { values += current }
            }
            .use { it.frame(1)?.close() }
        return values.single()
    }
}
