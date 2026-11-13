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
    fun gameStateReadsImmediatelyAndRefreshesAtItsIntervalAndAfterTasks() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding()
        state.open()
        state.open()
        assertEquals(0, reads)
        var observed = 0
        UiSession(Viewport(20, 20)) {
                val value by
                    state.gameState(intervalTicks = 3) {
                        reads++
                        energy
                    }
                val current = value
                SideEffect { observed = current }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals(1, reads)
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
    fun gameStateFollowsTheLatestReadBlockAndEndsWithTheComposition() {
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
                            gameState(intervalTicks = 10) {
                                reads++
                                energy * multiplier
                            }
                        val current = value
                        SideEffect { observed = current }
                    }
                }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals(7, observed)
                assertEquals(1, reads, "The first composition read more than once")
                state.tick()
                assertEquals(1, reads, "A new subscription was read again before its interval")
                factor.value = 2
                session.frame(2)?.close()
                assertTrue(state.gameThread.post {})
                state.tick()
                session.frame(3)?.close()
                assertEquals(14, observed, "The sample used an old read block")
                shown.value = false
                session.frame(4)?.close()
                assertTrue(state.gameThread.post {})
                state.tick()
                assertEquals(2, reads, "A subscription was sampled after it left the composition")
            }
        state.close()
    }

    @Test
    fun newKeysImmediatelyReplaceTheValueAndEndTheOldSubscription() {
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
                        gameState(10, key) {
                            sampled += key
                            energy.getValue(key)
                        }
                    val current = value
                    SideEffect { observed = current }
                }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals(1, observed)
                source.value = "b"
                session.frame(2)?.close()
                assertEquals(2, observed, "The replacement did not show its own first value")
                assertEquals(listOf("a", "b"), sampled)
                assertTrue(state.gameThread.post {})
                state.runTasks()
                session.frame(3)?.close()
                assertEquals(listOf("a", "b", "b"), sampled, "The old subscription was sampled after replacement")
            }
        state.close()
    }

    @Test
    fun changingTheIntervalImmediatelyReadsAndReplacesTheSubscription() {
        val state = UiStateBinding()
        state.open()
        val interval = mutableStateOf(10)
        var energy = 1
        var observed = 0
        var reads = 0
        UiSession(Viewport(20, 20)) {
                val value by
                    state.gameState(interval.value) {
                        reads++
                        energy
                    }
                val current = value
                SideEffect { observed = current }
            }
            .use { session ->
                session.frame(1)?.close()
                state.tick()
                energy = 2
                interval.value = 2
                session.frame(2)?.close()
                assertEquals(2, observed)
                assertEquals(2, reads)
                state.tick()
                assertEquals(2, reads)
                state.tick()
                assertEquals(3, reads)
            }
        state.close()
    }

    @Test
    fun samplingAnEqualValueDoesNotRecomposeItsReaders() {
        val state = UiStateBinding()
        state.open()
        var reads = 0
        var compositions = 0
        UiSession(Viewport(20, 20)) {
                val value by state.gameState {
                    reads++
                    "unchanged"
                }
                val current = value
                SideEffect {
                    assertEquals("unchanged", current)
                    compositions++
                }
            }
            .use { session ->
                session.frame(1)?.close()
                val first = compositions
                state.tick()
                session.frame(2)?.close()
                assertEquals(2, reads)
                assertEquals(first, compositions)
            }
        state.close()
    }

    @Test
    fun theInitialReadDoesNotSubscribeTheCompositionToItsSources() {
        val state = UiStateBinding()
        state.open()
        val source = mutableStateOf(1)
        var observed = 0
        UiSession(Viewport(20, 20)) {
                val value by state.gameState(intervalTicks = 10) { source.value }
                val current = value
                SideEffect { observed = current }
            }
            .use { session ->
                session.frame(1)?.close()
                source.value = 2
                session.frame(2)?.close()
                assertEquals(1, observed, "The initial read bypassed the sampling interval")
                repeat(10) { state.tick() }
                session.frame(3)?.close()
                assertEquals(2, observed)
            }
        state.close()
    }

    @Test
    fun closingKeepsTheLastValueAndANewSessionReadsTheGameAgain() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding()
        var shown: androidx.compose.runtime.State<Int>? = null
        fun scene() =
            UiSession(Viewport(20, 20)) {
                val value = state.gameState {
                    reads++
                    energy
                }
                val current = value
                SideEffect { shown = current }
            }
        state.open()
        scene().use { session ->
            session.frame(1)?.close()
            state.close()
            energy = 2
            state.tick()
            assertEquals(1, reads, "A closed session sampled the game")
            assertEquals(1, shown!!.value, "A closed session lost its last value")
        }
        state.open()
        scene().use { session ->
            session.frame(1)?.close()
            assertEquals(2, reads)
            assertEquals(2, shown!!.value)
        }
        state.close()
    }

    @Test
    fun showingTheSessionAgainRunsTasksAndRefreshesItsState() {
        var energy = 1
        var reads = 0
        val state = UiStateBinding()
        state.open()
        var observed = 0
        UiSession(Viewport(20, 20)) {
                val value by
                    state.gameState(intervalTicks = 100) {
                        reads++
                        energy
                    }
                val current = value
                SideEffect { observed = current }
            }
            .use { session ->
                session.frame(1)?.close()
                assertTrue(state.gameThread.post { energy = 3 })
                state.resume()
                session.frame(2)?.close()
                assertEquals(2, reads)
                assertEquals(3, observed)
                energy = 4
                state.resume()
                session.frame(3)?.close()
                assertEquals(3, reads, "Resuming without a task did not read the game")
                assertEquals(4, observed)
            }
        state.close()
    }

    @Test
    fun aTaskThatClosesTheSessionSkipsTheRemainingTasksAndReads() {
        var reads = 0
        val log = mutableListOf<String>()
        val state = UiStateBinding()
        state.open()
        var shown = 0
        UiSession(Viewport(20, 20)) {
                val value by state.gameState { ++reads }
                val current = value
                SideEffect { shown = current }
            }
            .use { session ->
                session.frame(1)?.close()
                assertTrue(state.gameThread.post { state.close() })
                assertTrue(state.gameThread.post { log += "after close" })
                state.runTasks()
                assertEquals(emptyList(), log)
                assertEquals(1, reads)
                assertEquals(1, shown)
                assertFalse(state.isOpen)
                assertFalse(state.gameThread.post {})
            }
    }

    @Test
    fun aSessionReopenedByATaskKeepsItsOwnFirstRead() {
        var reads = 0
        val state = UiStateBinding()
        var shown = 0
        fun scene() =
            UiSession(Viewport(20, 20)) {
                val value by state.gameState { ++reads }
                val current = value
                SideEffect { shown = current }
            }
        state.open()
        scene().use { original ->
            original.frame(1)?.close()
            var replacement: UiSession? = null
            try {
                assertTrue(
                    state.gameThread.post {
                        state.close()
                        state.open()
                        replacement = scene()
                    }
                )
                state.tick()
                replacement!!.frame(1)?.close()
                assertEquals(2, reads, "The step sampled the session its task had started")
                assertEquals(2, shown)
            } finally {
                replacement?.close()
            }
        }
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
