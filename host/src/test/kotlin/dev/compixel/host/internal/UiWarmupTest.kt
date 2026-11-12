package dev.compixel.host.internal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UiWarmupTest {
    @Test
    fun `each advance runs one step, in order`() {
        val log = mutableListOf<String>()
        val warmup = UiWarmup { error("No step fails") }
        warmup.step("open") { log += "open" }
        warmup.step("draw") { log += "draw" }
        warmup.step("close") { log += "close" }
        assertFalse(warmup.finished)
        assertTrue(warmup.advance())
        assertEquals(listOf("open"), log)
        assertTrue(warmup.advance())
        assertFalse(warmup.advance(), "The last step left work behind")
        assertEquals(listOf("open", "draw", "close"), log)
        assertTrue(warmup.finished)
        assertFalse(warmup.advance())
        assertEquals(3, warmup.frames)
        assertNull(warmup.failure)
    }

    @Test
    fun `a step can take several frames, up to its limit`() {
        var tries = 0
        val log = mutableListOf<String>()
        val warmup = UiWarmup { error("No step fails") }
        warmup.until("ready", atMost = 5) { ++tries == 3 }
        warmup.until("never", atMost = 2) {
            log += "never"
            false
        }
        warmup.step("after") { log += "after" }
        repeat(3) { assertTrue(warmup.advance()) }
        assertEquals(3, tries)
        assertTrue(warmup.advance())
        assertTrue(warmup.advance())
        assertFalse(warmup.advance())
        assertEquals(listOf("never", "never", "after"), log)
    }

    @Test
    fun `a failing step drops the rest but still releases what was opened`() {
        val log = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val problem = IllegalStateException("no GPU")
        val warmup = UiWarmup { errors += it }
        warmup.step("open") { log += "open" }
        warmup.step("draw") { throw problem }
        warmup.step("draw more") { log += "draw more" }
        warmup.step("close", always = true) { log += "close" }
        assertTrue(warmup.advance())
        assertTrue(warmup.advance(), "The release step was dropped with the others")
        assertFalse(warmup.advance())
        assertEquals(listOf("open", "close"), log)
        assertEquals(listOf<Throwable>(problem), errors)
        assertSame(problem, warmup.failure)
        assertTrue(warmup.finished)
    }

    @Test
    fun `the longest step is recorded`() {
        val warmup = UiWarmup { error("No step fails") }
        warmup.step("quick") {}
        warmup.step("slow") { Thread.sleep(20) }
        while (warmup.advance()) Unit
        assertEquals("slow", warmup.longestStep)
        assertTrue(warmup.longestNanos >= 15_000_000L)
        assertTrue(warmup.totalNanos >= warmup.longestNanos)
    }
}
