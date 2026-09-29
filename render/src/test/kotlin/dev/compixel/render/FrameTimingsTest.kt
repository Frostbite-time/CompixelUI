package dev.compixel.render

import kotlin.test.*
import org.junit.jupiter.api.Test

class FrameTimingsTest {
    @Test
    fun oldOutliersLeaveTheBoundedWindow() {
        val timings = FrameTimings(4)
        assertEquals(0, timings.summary().samples)
        listOf(999L, 4L, 1L, 2L, 3L).forEach(timings::record)
        assertEquals(TimingSummary(4, 2, 4, 4), timings.summary())
        assertFailsWith<IllegalArgumentException> { timings.record(-1) }
    }
}
