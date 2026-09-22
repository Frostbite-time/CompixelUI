package dev.composemc.render

import org.junit.jupiter.api.Test
import kotlin.test.*

class UiFrameProfilerTest {
    @Test fun delayedGpuResultsStayWithTheirFrameAndMissingValuesAreExplicit() {
        val profiler = UiFrameProfiler()
        profiler.beginFrame()
        val first = profiler.gpuRequested(GpuPhase.RENDER)
        profiler.gpuRequested(GpuPhase.PRESENT)
        profiler.endFrame()
        profiler.beginFrame()
        val second = profiler.gpuRequested(GpuPhase.RENDER)
        profiler.endFrame()
        profiler.gpuCompleted(first, GpuPhase.RENDER, 11)
        profiler.gpuCompleted(second, GpuPhase.RENDER, 23)
        val rows = profiler.frames()
        assertEquals(11L, rows[0].gpu[GpuPhase.RENDER])
        assertNull(rows[0].gpu[GpuPhase.PRESENT])
        assertEquals(1, rows[0].missingGpuResults)
        assertEquals(23L, rows[1].gpu[GpuPhase.RENDER])
        assertEquals(0L, rows[1].gpu[GpuPhase.PRESENT])
    }

    @Test fun multipleImportsAccumulateAndEvictedGpuResultsCannotPolluteNewFrames() {
        val profiler = UiFrameProfiler(capacity = 1)
        profiler.beginFrame()
        val discarded = profiler.gpuRequested(GpuPhase.IMAGE_IMPORT)
        profiler.endFrame()
        profiler.beginFrame()
        val active = profiler.gpuRequested(GpuPhase.IMAGE_IMPORT)
        profiler.gpuRequested(GpuPhase.IMAGE_IMPORT)
        profiler.endFrame()
        profiler.gpuCompleted(discarded, GpuPhase.IMAGE_IMPORT, 999)
        profiler.gpuCompleted(active, GpuPhase.IMAGE_IMPORT, 7)
        assertNull(profiler.frames().single().gpu[GpuPhase.IMAGE_IMPORT])
        profiler.gpuCompleted(active, GpuPhase.IMAGE_IMPORT, 13)
        assertEquals(20L, profiler.frames().single().gpu[GpuPhase.IMAGE_IMPORT])
        assertEquals(1L, profiler.discardedGpuResults)
    }

    @Test fun dispatchWaitIsSeparateFromExecutionAndDetailsAreNotAddedToCpuSpans() {
        val profiler = UiFrameProfiler()
        profiler.beginFrame()
        profiler.composeCall(100, 40, 80)
        profiler.recorded(7)
        profiler.recorded(8)
        profiler.rendered()
        profiler.endFrame()
        val row = profiler.frames().single()
        assertEquals(40L, row.details[CpuDetail.EDT_EXECUTION])
        assertEquals(60L, row.details[CpuDetail.EDT_WAIT])
        assertEquals(80L, row.composeThreadBytes)
        assertEquals(0L, row.cpu.values.sum())
        assertEquals(2, row.recordings)
        assertEquals(8L, row.generation)
        assertTrue(row.rendered)
    }
}
