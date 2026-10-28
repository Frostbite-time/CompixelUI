package dev.compixel.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    fun `handling an input event's actions publishes one snapshot before the next tick`() {
        var energy = 1
        var snapshots = 0
        val handled = mutableListOf<Int>()
        val state =
            UiStateBinding<Int, Int>({
                snapshots++
                energy
            }) {
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
                state.handleActions()
                assertEquals(1, snapshots, "A snapshot was taken without an action")
                assertTrue(ComposeThread.call { state.send(10) && state.send(5) })
                state.handleActions()
                assertEquals(listOf(10, 5), handled)
                assertEquals(2, snapshots)
                session.frame(2)?.close()
                assertEquals(16, observed)
            }
        state.close()
    }

    @Test
    fun `handling actions stops once the handler ends the session`() {
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
        state.handleActions()
        assertEquals(listOf(1), handled)
        assertEquals(1, snapshots)
        assertFalse(state.isOpen)
        state.handleActions()
        assertEquals(listOf(1), handled)
    }

    @Test
    fun `rejected actions are counted for each session`() {
        val state = UiStateBinding<Int, Int>({ 0 }) {}
        state.open()
        repeat(64) { assertTrue(state.send(it)) }
        assertFalse(state.send(64))
        assertEquals(1, state.rejectedActions)
        state.close()
        assertFalse(state.send(65))
        assertEquals(1, state.rejectedActions, "A send after close counted as a rejection")
        state.open()
        assertEquals(0, state.rejectedActions)
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
            assertFailsWith<IllegalStateException> { state.handleActions() }
            assertFailsWith<IllegalStateException> { state.close() }
        }
        assertTrue(state.isOpen)
        state.close()
    }

    @Test
    fun `tasks share the actions' queue and run in order, and a task queued by a task waits for the next step`() {
        val log = mutableListOf<String>()
        lateinit var state: UiStateBinding<Int, Int>
        state = UiStateBinding({ 0 }) { log += "action $it" }
        assertFalse(state.gameThread.post { log += "early" }, "A task was accepted before the session opened")
        state.open()
        assertTrue(state.send(1))
        assertTrue(
            state.gameThread.post {
                log += "task"
                state.gameThread.post { log += "queued by a task" }
            }
        )
        assertTrue(state.send(2))
        state.tick()
        assertEquals(listOf("action 1", "task", "action 2"), log)
        state.tick()
        assertEquals("queued by a task", log.last())
        repeat(63) { assertTrue(state.send(it)) }
        assertTrue(state.gameThread.post {})
        assertFalse(state.gameThread.post {}, "A task was queued beyond the queue's limit")
        assertEquals(1, state.rejectedActions)
        state.close()
        assertFalse(state.gameThread.post {})
    }

    @Test
    fun `member game state shows the game from the first composition and refreshes at its interval and after tasks`() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding<Unit, Nothing>({}) {}
        val shown by
            state.gameState(intervalTicks = 3) {
                reads++
                energy
            }
        state.open()
        assertEquals(1, reads)
        var observed = 0
        UiSession(Viewport(20, 20)) {
                val value = shown
                SideEffect { observed = value }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals(1, observed, "The first composition did not show the game")
                energy = 2
                state.tick()
                state.tick()
                assertEquals(1, reads, "The state was sampled before its interval")
                state.tick()
                assertEquals(2, reads)
                session.frame(2)?.close()
                assertEquals(2, observed)
                state.handleActions()
                assertEquals(2, reads, "An input event without actions or tasks sampled the state")
                assertTrue(ComposeThread.call { state.gameThread.post { energy = 5 } })
                state.handleActions()
                assertEquals(3, reads)
                session.frame(3)?.close()
                assertEquals(5, observed, "A task's change did not show at once")
                assertTrue(state.gameThread.post { energy = 6 })
                state.tick()
                assertEquals(4, reads, "A tick that ran a task did not refresh the state")
            }
        state.close()
    }

    @Test
    fun `composed game state starts from its initial value, follows the latest read block and ends with the composition`() {
        val energy = 7
        var reads = 0
        val state = UiStateBinding<Unit, Nothing>({}) {}
        state.open()
        val shown = ComposeThread.call { mutableStateOf(true) }
        val factor = ComposeThread.call { mutableStateOf(1) }
        var observed = 0
        UiSession(Viewport(20, 20)) {
                CompositionLocalProvider(LocalGameStates provides state) {
                    if (shown.value) {
                        val multiplier = factor.value
                        val value by
                            gameState(initialValue = -1, intervalTicks = 10) {
                                reads++
                                energy * multiplier
                            }
                        // Read during composition, as content does, so a change recomposes.
                        val current = value
                        SideEffect { observed = current }
                    }
                }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals(-1, observed)
                state.tick()
                assertEquals(1, reads, "A new subscription waited for its interval")
                session.frame(2)?.close()
                assertEquals(7, observed)
                ComposeThread.call { factor.value = 2 }
                session.frame(3)?.close()
                assertTrue(state.gameThread.post {})
                state.tick()
                session.frame(4)?.close()
                assertEquals(14, observed, "The sample used an old read block")
                ComposeThread.call { shown.value = false }
                session.frame(5)?.close()
                assertTrue(state.gameThread.post {})
                state.tick()
                assertEquals(2, reads, "A subscription was sampled after it left the composition")
            }
        state.close()
    }

    @Test
    fun `new keys replace a subscription, and the old one is not sampled into the new one`() {
        val energy = mapOf("a" to 1, "b" to 2)
        val state = UiStateBinding<Unit, Nothing>({}) {}
        state.open()
        val source = ComposeThread.call { mutableStateOf("a") }
        val sampled = mutableListOf<String>()
        var observed = 0
        UiSession(Viewport(20, 20)) {
                CompositionLocalProvider(LocalGameStates provides state) {
                    val key = source.value
                    val value by
                        gameState(0, 1, key) {
                            sampled += key
                            energy.getValue(key)
                        }
                    val current = value
                    SideEffect { observed = current }
                }
            }
            .use { session ->
                session.frame(1)?.close()
                state.tick()
                session.frame(2)?.close()
                assertEquals(1, observed)
                ComposeThread.call { source.value = "b" }
                session.frame(3)?.close()
                assertEquals(0, observed, "A replaced subscription kept the old value")
                state.tick()
                session.frame(4)?.close()
                assertEquals(2, observed)
                assertEquals(listOf("a", "b"), sampled, "The old subscription was sampled after it was replaced")
            }
        state.close()
    }

    @Test
    fun `a closed session keeps its last values and a reopened one samples the game again`() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding<Unit, Nothing>({}) {}
        val shown by state.gameState {
            reads++
            energy
        }
        state.open()
        state.close()
        energy = 2
        state.tick()
        assertEquals(1, reads, "A closed session sampled the game")
        assertEquals(1, shown, "A closed session lost its last value")
        state.open()
        assertEquals(2, reads)
        assertEquals(2, shown)
        state.close()
    }

    @Test
    fun `a session shown again runs its waiting tasks and then refreshes everything`() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding<Unit, Nothing>({}) {}
        val shown by
            state.gameState(intervalTicks = 100) {
                reads++
                energy
            }
        state.open()
        assertTrue(state.gameThread.post { energy = 3 })
        state.resume()
        assertEquals(2, reads)
        assertEquals(3, shown)
        state.close()
    }

    @Test
    fun `a task that ends the session skips the remaining tasks and the sampling`() {
        var reads = 0
        val log = mutableListOf<String>()
        lateinit var state: UiStateBinding<Unit, Nothing>
        state = UiStateBinding({}) {}
        val shown by state.gameState { ++reads }
        state.open()
        assertTrue(state.gameThread.post { state.close() })
        assertTrue(state.gameThread.post { log += "after close" })
        state.handleActions()
        assertEquals(emptyList(), log)
        assertEquals(1, reads)
        assertEquals(1, shown)
    }

    @Test
    fun `member game state is declared while its host is constructed and read once a session opened`() {
        val state = UiStateBinding<Unit, Nothing>({}) {}
        val early by state.gameState { 1 }
        assertFailsWith<IllegalStateException> { early }
        ComposeThread.call { assertFailsWith<IllegalStateException> { state.gameState { 2 } } }
        state.open()
        assertEquals(1, early)
        assertFailsWith<IllegalStateException> { state.gameState { 3 } }
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
