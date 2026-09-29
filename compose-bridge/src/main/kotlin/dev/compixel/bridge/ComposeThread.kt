package dev.compixel.bridge

import dev.compixel.render.JvmAllocations
import dev.compixel.render.UiFrameProfiler
import java.awt.EventQueue
import java.util.concurrent.FutureTask

/** Scene and Skiko delayed callbacks share the EDT. Never call back into a waiting game thread. */
object ComposeThread {
    private val observer = ThreadLocal<UiFrameProfiler?>()

    /** The observer is scoped to the calling thread; it never changes Compose's dispatcher. */
    fun <T> traceCalls(profiler: UiFrameProfiler, block: () -> T): T {
        val previous = observer.get()
        observer.set(profiler)
        return try {
            block()
        } finally {
            if (previous == null) observer.remove() else observer.set(previous)
        }
    }

    fun <T> call(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val profiler = observer.get()
        var execution = 0L
        var allocated = -1L
        val started = if (profiler != null) System.nanoTime() else 0
        val task =
            if (profiler == null) FutureTask(block)
            else
                FutureTask {
                    val beforeBytes = if (profiler.measureAllocations) JvmAllocations.currentBytes() else -1
                    val before = System.nanoTime()
                    try {
                        block()
                    } finally {
                        execution = System.nanoTime() - before
                        if (beforeBytes >= 0) allocated = JvmAllocations.currentBytes() - beforeBytes
                    }
                }
        EventQueue.invokeAndWait(task)
        return try {
            task.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        } finally {
            profiler?.composeCall(System.nanoTime() - started, execution, allocated)
        }
    }

    internal fun check() = check(EventQueue.isDispatchThread()) { "Compose accessed outside EDT" }
}
