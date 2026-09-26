package dev.composemc.render

import java.lang.management.ManagementFactory

/** Top-level CPU wall-time spans are disjoint. Details are subsets and must not be added again. */
enum class CpuPhase {
    HOST,
    RECORD,
    ITEMS,
    TOOLTIP,
    RENDER,
    PRESENT,
    FRAME_RELEASE,
}

enum class CpuDetail {
    EDT_EXECUTION,
    EDT_WAIT,
    GL_CAPTURE,
    GL_RESTORE,
    NATIVE_DRAW,
    IMAGE_IMPORT,
    IMAGE_RETIRE,
}

enum class GpuPhase {
    ITEMS,
    TOOLTIP,
    IMAGE_IMPORT,
    RENDER,
    PRESENT,
}

inline fun <T> UiFrameProfiler?.measureCpu(phase: CpuPhase, crossinline action: () -> T): T =
    if (this == null) action() else cpu(phase) { action() }

inline fun <T> UiFrameProfiler?.measureDetail(phase: CpuDetail, crossinline action: () -> T): T =
    if (this == null) action() else detail(phase) { action() }

data class UiFrameProfile(
    val frameId: Long,
    val startedNanos: Long,
    val totalNanos: Long,
    val cpu: Map<CpuPhase, Long>,
    val details: Map<CpuDetail, Long>,
    val gpu: Map<GpuPhase, Long?>,
    val gpuRequests: Map<GpuPhase, Int>,
    val missingGpuResults: Int,
    val renderThreadBytes: Long?,
    val composeThreadBytes: Long?,
    val composeCalls: Int,
    val recordings: Int,
    val rendered: Boolean,
    val generation: Long,
    val activeItems: Int,
    val cachedItems: Int,
    val pendingItems: Int,
    val tooltipVisible: Boolean,
)

/** Opt-in, bounded, render-thread-owned history. Async GPU samples retain their originating frame ID. */
class UiFrameProfiler(private val capacity: Int = 2048, val measureAllocations: Boolean = false) {
    private class Row(val id: Long, val started: Long, val allocationStart: Long) {
        val cpu = LongArray(CpuPhase.entries.size)
        val detail = LongArray(CpuDetail.entries.size)
        val gpu = LongArray(GpuPhase.entries.size)
        val requested = IntArray(GpuPhase.entries.size)
        val received = IntArray(GpuPhase.entries.size)
        var elapsed = 0L
        var renderBytes: Long? = null
        var composeBytes: Long? = null
        var calls = 0
        var recordings = 0
        var rendered = false
        var generation = 0L
        var activeItems = 0
        var cachedItems = 0
        var pendingItems = 0
        var tooltipVisible = false
    }

    private val owner = Thread.currentThread()
    private val history = linkedMapOf<Long, Row>()
    private var current: Row? = null
    private var sequence = 0L
    private var lastGeneration = 0L
    val frameId: Long
        get() = current?.id ?: 0L

    val lastFrameId: Long
        get() = sequence

    var discardedGpuResults = 0L
        private set

    init {
        require(capacity > 0)
    }

    fun beginFrame() {
        checkOwner()
        check(current == null) { "A profile frame is already open" }
        val allocated = if (measureAllocations) JvmAllocations.currentBytes() else -1
        val row = Row(++sequence, System.nanoTime(), allocated)
        row.generation = lastGeneration
        current = row
        history[row.id] = row
        if (history.size > capacity) history.remove(history.keys.first())
    }

    fun endFrame() {
        checkOwner()
        val row = checkNotNull(current)
        row.elapsed = System.nanoTime() - row.started
        if (row.allocationStart >= 0)
            row.renderBytes =
                JvmAllocations.currentBytes().takeIf { it >= row.allocationStart }?.minus(row.allocationStart)
        current = null
    }

    fun <T> cpu(phase: CpuPhase, block: () -> T): T {
        val row = current ?: return block()
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            row.cpu[phase.ordinal] += System.nanoTime() - start
        }
    }

    fun <T> detail(phase: CpuDetail, block: () -> T): T {
        val row = current ?: return block()
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            row.detail[phase.ordinal] += System.nanoTime() - start
        }
    }

    fun addDetail(phase: CpuDetail, nanos: Long) {
        current?.let { it.detail[phase.ordinal] += nanos }
    }

    fun composeCall(roundTripNanos: Long, executionNanos: Long, allocatedBytes: Long) {
        current?.let {
            it.calls++
            it.detail[CpuDetail.EDT_EXECUTION.ordinal] += executionNanos
            it.detail[CpuDetail.EDT_WAIT.ordinal] += (roundTripNanos - executionNanos).coerceAtLeast(0)
            if (allocatedBytes >= 0) it.composeBytes = (it.composeBytes ?: 0) + allocatedBytes
        }
    }

    fun recorded(generation: Long) {
        lastGeneration = generation
        current?.let {
            it.recordings++
            it.generation = generation
        }
    }

    fun rendered() {
        current?.rendered = true
    }

    fun resources(activeItems: Int, cachedItems: Int, pendingItems: Int, tooltipVisible: Boolean) {
        current?.let {
            it.activeItems = activeItems
            it.cachedItems = cachedItems
            it.pendingItems = pendingItems
            it.tooltipVisible = tooltipVisible
        }
    }

    /** Call even if a query pool is exhausted: missing measurements must not be reported as zero. */
    fun gpuRequested(phase: GpuPhase): Long {
        val row = current ?: return 0
        row.requested[phase.ordinal]++
        return row.id
    }

    fun gpuCompleted(frameId: Long, phase: GpuPhase, nanos: Long) {
        checkOwner()
        if (frameId == 0L) return
        require(nanos >= 0)
        val row = history[frameId]
        if (row == null) {
            discardedGpuResults++
            return
        }
        check(row.received[phase.ordinal] < row.requested[phase.ordinal]) {
            "Unexpected GPU sample for frame $frameId/$phase"
        }
        row.gpu[phase.ordinal] += nanos
        row.received[phase.ordinal]++
    }

    fun frames(): List<UiFrameProfile> {
        checkOwner()
        return history.values
            .filter { it !== current }
            .map { row ->
                UiFrameProfile(
                    row.id,
                    row.started,
                    row.elapsed,
                    CpuPhase.entries.associateWith { row.cpu[it.ordinal] },
                    CpuDetail.entries.associateWith { row.detail[it.ordinal] },
                    GpuPhase.entries.associateWith { phase ->
                        val i = phase.ordinal
                        if (row.received[i] == row.requested[i]) row.gpu[i] else null
                    },
                    GpuPhase.entries.associateWith { row.requested[it.ordinal] },
                    row.requested.indices.sumOf { row.requested[it] - row.received[it] },
                    row.renderBytes,
                    row.composeBytes,
                    row.calls,
                    row.recordings,
                    row.rendered,
                    row.generation,
                    row.activeItems,
                    row.cachedItems,
                    row.pendingItems,
                    row.tooltipVisible,
                )
            }
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Frame profiler accessed outside its owner" }
}

/** Optional JVM allocation counters; unsupported measurements remain absent rather than zero. */
object JvmAllocations {
    private val bean: com.sun.management.ThreadMXBean? by lazy {
        runCatching {
            (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
                ?.takeIf { it.isThreadAllocatedMemorySupported }
                ?.also {
                    if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                }
        }
            .getOrNull()
    }

    @Suppress("DEPRECATION") fun currentBytes(): Long = bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1
}
