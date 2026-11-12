package dev.compixel.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import dev.compixel.platform.Viewport
import kotlin.test.*
import org.junit.jupiter.api.Test

class UiStateBindingTest {
    @Test
    fun `tasks run in order, and a task queued by a task waits for the next step`() {
        val log = mutableListOf<String>()
        val state = UiStateBinding()
        assertFalse(state.gameThread.post { log += "early" }, "A task was accepted before the session opened")
        state.open()
        assertTrue(state.gameThread.post { log += "first" })
        assertTrue(
            state.gameThread.post {
                log += "second"
                state.gameThread.post { log += "queued by a task" }
            }
        )
        assertTrue(state.gameThread.post { log += "third" })
        state.tick()
        assertEquals(listOf("first", "second", "third"), log)
        state.tick()
        assertEquals("queued by a task", log.last())
        state.close()
        assertFalse(state.gameThread.post {})
    }

    @Test
    fun `rejected tasks are counted for each session`() {
        val state = UiStateBinding()
        state.open()
        repeat(64) { assertTrue(state.gameThread.post {}) }
        assertFalse(state.gameThread.post {}, "A task was queued beyond the queue's limit")
        assertEquals(1, state.rejectedTasks)
        state.close()
        assertFalse(state.gameThread.post {})
        assertEquals(1, state.rejectedTasks, "A task posted after close counted as a rejection")
        state.open()
        assertEquals(0, state.rejectedTasks)
        state.close()
    }

    @Test
    fun `closing discards pending tasks and a reopened session runs only its own`() {
        val log = mutableListOf<Int>()
        val state = UiStateBinding()
        state.open()
        assertTrue(state.gameThread.post { log += 1 })
        state.close()
        state.tick()
        assertEquals(emptyList(), log)
        state.open()
        assertTrue(state.gameThread.post { log += 3 })
        state.tick()
        assertEquals(listOf(3), log)
        state.close()
    }

    @Test
    fun `tasks can come from another thread while ticks and close stay on the opening thread`() {
        val state = UiStateBinding()
        var ran = false
        state.open()
        onAnotherThread {
            assertTrue(state.gameThread.post { ran = true })
            assertFailsWith<IllegalStateException> { state.tick() }
            assertFailsWith<IllegalStateException> { state.runTasks() }
            assertFailsWith<IllegalStateException> { state.close() }
        }
        assertTrue(state.isOpen)
        state.tick()
        assertTrue(ran)
        state.close()
    }

    @Test
    fun `member game state shows the game from the first composition and refreshes at its interval and after tasks`() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding()
        val shown by
            state.gameState(intervalTicks = 3) {
                reads++
                energy
            }
        state.open()
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
                state.runTasks()
                assertEquals(2, reads, "An input event without tasks sampled the state")
                assertTrue(state.gameThread.post { energy = 5 })
                state.runTasks()
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
        val state = UiStateBinding()
        state.open()
        val shown = mutableStateOf(true)
        val factor = mutableStateOf(1)
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
                factor.value = 2
                session.frame(3)?.close()
                assertTrue(state.gameThread.post {})
                state.tick()
                session.frame(4)?.close()
                assertEquals(14, observed, "The sample used an old read block")
                shown.value = false
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
        val state = UiStateBinding()
        state.open()
        val source = mutableStateOf("a")
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
                source.value = "b"
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
        val state = UiStateBinding()
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
        val state = UiStateBinding()
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
        val state = UiStateBinding()
        val shown by state.gameState { ++reads }
        state.open()
        assertTrue(state.gameThread.post { state.close() })
        assertTrue(state.gameThread.post { log += "after close" })
        state.runTasks()
        assertEquals(emptyList(), log)
        assertEquals(1, reads)
        assertEquals(1, shown)
        assertFalse(state.isOpen)
        assertFalse(state.gameThread.post {})
    }

    @Test
    fun `a session reopened by a task keeps its own sample`() {
        var reads = 0
        val state = UiStateBinding()
        val shown by state.gameState { ++reads }
        state.open()
        assertTrue(
            state.gameThread.post {
                state.close()
                state.open()
            }
        )
        state.tick()
        assertEquals(2, reads, "The step sampled the session its task had started")
        assertEquals(2, shown)
        state.close()
    }

    @Test
    fun `member game state is declared before the first session and read once a session opened`() {
        val state = UiStateBinding()
        val early by state.gameState { 1 }
        assertFailsWith<IllegalStateException> { early }
        state.open()
        assertEquals(1, early)
        assertFailsWith<IllegalStateException> { state.gameState { 3 } }
        state.close()
    }
}

/** Runs [block] on a thread that is not the UI's and rethrows what it threw. */
internal fun onAnotherThread(block: () -> Unit) {
    var failure: Throwable? = null
    Thread {
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        }
    }
        .apply {
            start()
            join()
        }
    failure?.let { throw it }
}
