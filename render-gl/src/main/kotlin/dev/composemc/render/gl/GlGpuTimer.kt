package dev.composemc.render.gl

import dev.composemc.render.FrameTimings
import dev.composemc.render.GpuPhase
import dev.composemc.render.UiFrameProfiler
import org.lwjgl.opengl.GL33C.*

/** Timestamp pairs avoid nesting a TIME_ELAPSED query owned by another renderer. */
internal class GlGpuTimer(
    private val profiler: UiFrameProfiler? = null,
    private val phase: GpuPhase = GpuPhase.RENDER,
    poolSize: Int = 8,
) : AutoCloseable {
    private data class Queries(val start: Int, val end: Int, var frameId: Long = 0)

    private val queries = List(poolSize) { Queries(glGenQueries(), glGenQueries()) }
    private val available = ArrayDeque(queries)
    private val pending = ArrayDeque<Queries>()
    val timings = FrameTimings()

    fun <T> measure(action: () -> T): T {
        collect()
        val frameId = profiler?.gpuRequested(phase) ?: 0L
        // Never wait for the GPU or grow an unbounded query queue.
        val query = available.removeFirstOrNull() ?: return action()
        query.frameId = frameId
        glQueryCounter(query.start, GL_TIMESTAMP)
        return try {
            action()
        } finally {
            glQueryCounter(query.end, GL_TIMESTAMP)
            pending.addLast(query)
        }
    }

    fun collect() {
        while (pending.isNotEmpty()) {
            val query = pending.first()
            if (glGetQueryObjecti(query.end, GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) break
            val elapsed =
                glGetQueryObjectui64(query.end, GL_QUERY_RESULT) - glGetQueryObjectui64(query.start, GL_QUERY_RESULT)
            if (elapsed >= 0) {
                timings.record(elapsed)
                profiler?.gpuCompleted(query.frameId, phase, elapsed)
            }
            pending.removeFirst()
            available.addLast(query)
        }
    }

    override fun close() {
        collect()
        queries.forEach {
            glDeleteQueries(it.start)
            glDeleteQueries(it.end)
        }
        pending.clear()
        available.clear()
    }
}
