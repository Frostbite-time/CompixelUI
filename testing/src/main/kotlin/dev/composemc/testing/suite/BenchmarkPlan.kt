package dev.composemc.testing.suite

import dev.composemc.render.CpuDetail
import dev.composemc.render.CpuPhase
import dev.composemc.render.GpuPhase
import dev.composemc.render.UiFrameProfile
import dev.composemc.testing.ui.oreComponentPages
import java.io.File

enum class BenchmarkKind {
    STATIC,
    ANIMATION,
    LIST,
    NATIVE_STATIC,
    NATIVE_SCROLL,
    NATIVE_ANIMATED,
    TOOLTIP,
    ORE_COMPONENTS,
}

data class BenchmarkCase(val name: String, val kind: BenchmarkKind, val count: Int = 0)

/** One measurement protocol for every adapter: the same cases, viewport, pacing, warmup and sample counts. */
object BenchmarkPlan {
    const val WIDTH = 1280
    const val HEIGHT = 960
    const val GUI_SCALE = 2
    const val FRAME_LIMIT = 60
    const val WARMUP_FRAMES = 120
    const val PIPELINE_WARMUP_FRAMES = 2400
    const val GPU_DRAIN_FRAMES = 16
    const val TOOLTIP_HOVER_FRAME = 30
    val pipelineWarmup = BenchmarkCase("pipeline-warmup", BenchmarkKind.NATIVE_SCROLL, 10000)
    val cases: List<BenchmarkCase> =
        listOf(
            BenchmarkCase("static-ui", BenchmarkKind.STATIC),
            BenchmarkCase("animation", BenchmarkKind.ANIMATION),
            BenchmarkCase("list-1k", BenchmarkKind.LIST, 1000),
            BenchmarkCase("list-10k", BenchmarkKind.LIST, 10000),
            BenchmarkCase("list-100k", BenchmarkKind.LIST, 100000),
            BenchmarkCase("native-static", BenchmarkKind.NATIVE_STATIC, 1000),
            BenchmarkCase("native-scroll-10k", BenchmarkKind.NATIVE_SCROLL, 10000),
            BenchmarkCase("native-animated", BenchmarkKind.NATIVE_ANIMATED, 1000),
            BenchmarkCase("rich-tooltip", BenchmarkKind.TOOLTIP),
        ) +
            oreComponentPages.map {
                BenchmarkCase("ore-${it.name.lowercase()}", BenchmarkKind.ORE_COMPONENTS, it.ordinal)
            }

    fun samples(): Int =
        Integer.getInteger("composemc.benchmark.frames", 360).also {
            require(it in 120..1500) { "composemc.benchmark.frames must be 120..1500, got $it" }
        }

    fun repeats(): Int =
        Integer.getInteger("composemc.benchmark.repeats", 2).also {
            require(it in 1..5) { "composemc.benchmark.repeats must be 1..5, got $it" }
        }

    /** Repeats alternate forward and reverse order so drift does not favour early or late cases. */
    fun schedule(repeats: Int): List<Pair<Int, BenchmarkCase>> =
        (0 until repeats).flatMap { repeat ->
            (if (repeat % 2 == 0) cases else cases.reversed()).map { repeat to it }
        }

    val notes =
        listOf(
            "CPU spans are wall time inside Screen.render, excluding frame limiting, report export and screenshots.",
            "CPU details overlap top-level spans. GPU values are disjoint command-stream intervals, not busy time; do not add CPU and GPU times.",
            "Zero GPU requests means the stage did not run. Backends without GPU timers report no GPU columns.",
            "CSV rows join CPU and delayed GPU measurements by the originating host frame ID. No glFinish or blocking query reads are used.",
            "Every case runs in the same fresh flat creative world at render distance 2, with logical focus: hidden and visible runs measure the same frames.",
        )
}

/** Conditions that make a sample trustworthy. They validate the measurement, not product features. */
object BenchmarkValidity {
    fun check(
        case: BenchmarkCase,
        rows: List<UiFrameProfile>,
        samples: Int,
        activeVariants: Int,
        dynamicVariants: Int,
        refreshesDuringSamples: Long,
    ) {
        check(rows.size == samples) { "Incomplete samples for ${case.name}: ${rows.size}/$samples" }
        check(rows.all { it.missingGpuResults == 0 }) { "GPU results did not drain; ${case.name} is incomplete" }
        check(rows.maxOf { it.recordings } <= 2) { "${case.name} recorded more than twice in one frame" }
        check(rows.maxOf { it.cachedItems } <= if (case.kind == BenchmarkKind.NATIVE_ANIMATED) 512 else 128) {
            "${case.name} exceeded its native item cache"
        }
        if (case.kind == BenchmarkKind.STATIC || case.kind == BenchmarkKind.NATIVE_STATIC)
            check(rows.none { it.rendered }) { "Static fixture ${case.name} kept repainting" }
        if (case.kind in setOf(BenchmarkKind.LIST, BenchmarkKind.ANIMATION, BenchmarkKind.NATIVE_ANIMATED))
            check(rows.count { it.rendered } > samples * 9 / 10) {
                "Animated/scroll fixture ${case.name} did not advance"
            }
        if (case.kind == BenchmarkKind.NATIVE_ANIMATED) {
            // Every one of the 256 distinct icons must be prepared and keep refreshing in turn.
            check(activeVariants == 256 && dynamicVariants == activeVariants) {
                "Animated icons were starved: active=$activeVariants dynamic=$dynamicVariants"
            }
            check(refreshesDuringSamples >= activeVariants) {
                "Animated icons stopped refreshing: $refreshesDuringSamples refreshes"
            }
        }
        if (case.kind == BenchmarkKind.TOOLTIP)
            check(rows.all { it.tooltipVisible }) {
                "Tooltip benchmark lost hover: visible=${rows.count { it.tooltipVisible }}/${rows.size}"
            }
    }
}

/** Raw rows and summaries in the format read by tools/summarize_benchmarks.py. */
object BenchmarkRecords {
    fun summary(values: List<Long>): Map<String, Any>? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun percentile(p: Int) = sorted[((sorted.size * p + 99) / 100 - 1).coerceAtLeast(0)]
        return linkedMapOf(
            "samples" to values.size,
            "nonzero" to values.count { it != 0L },
            "p50" to percentile(50),
            "p95" to percentile(95),
            "p99" to percentile(99),
            "mean" to values.average(),
        )
    }

    /** GPU columns exist only when every requested interval returned; otherwise the backend has no GPU timers. */
    fun gpuTimed(rows: List<UiFrameProfile>): Boolean =
        rows.isNotEmpty() &&
            rows.all { row ->
                GpuPhase.entries.all { phase -> row.gpuRequests.getValue(phase) == 0 || row.gpu[phase] != null }
            } &&
            rows.any { row -> GpuPhase.entries.any { row.gpuRequests.getValue(it) > 0 } }

    fun metrics(rows: List<UiFrameProfile>): Map<String, Any?> {
        val metrics =
            linkedMapOf<String, Any?>(
                "total_cpu_wall_ns" to summary(rows.map { it.totalNanos }),
                "frame_interval_ns" to summary(rows.zipWithNext { a, b -> b.startedNanos - a.startedNanos }),
            )
        CpuPhase.entries.forEach { phase ->
            metrics["cpu_${phase.name.lowercase()}_ns"] = summary(rows.map { it.cpu.getValue(phase) })
        }
        CpuDetail.entries.forEach { phase ->
            metrics["detail_${phase.name.lowercase()}_ns"] = summary(rows.map { it.details.getValue(phase) })
        }
        if (gpuTimed(rows)) {
            GpuPhase.entries.forEach { phase ->
                metrics["gpu_${phase.name.lowercase()}_ns"] = summary(rows.mapNotNull { it.gpu[phase] })
            }
            metrics["gpu_stage_sum_ns"] = summary(rows.map { row -> row.gpu.values.sumOf { it ?: 0L } })
        }
        metrics["render_thread_bytes"] = summary(rows.mapNotNull { it.renderThreadBytes })
        metrics["compose_thread_bytes"] = summary(rows.mapNotNull { it.composeThreadBytes })
        metrics["compose_calls"] = summary(rows.map { it.composeCalls.toLong() })
        return metrics
    }

    fun writeCsv(file: File, rows: List<UiFrameProfile>) {
        val gpu = gpuTimed(rows)
        file.bufferedWriter().use { out ->
            out.appendLine(
                (listOf("frame_id", "start_relative_ns", "total_cpu_wall_ns") +
                        CpuPhase.entries.map { "cpu_${it.name.lowercase()}_ns" } +
                        CpuDetail.entries.map { "detail_${it.name.lowercase()}_ns" } +
                        GpuPhase.entries.map { "gpu_${it.name.lowercase()}_ns" } +
                        listOf(
                            "render_bytes",
                            "compose_bytes",
                            "compose_calls",
                            "recordings",
                            "rendered",
                            "generation",
                            "active_items",
                            "cached_items",
                            "pending_items",
                            "tooltip_visible",
                            "missing_gpu",
                        ))
                    .joinToString(",")
            )
            rows.forEach { row ->
                val values =
                    listOf<Any?>(row.frameId, row.startedNanos - rows.first().startedNanos, row.totalNanos) +
                        CpuPhase.entries.map { row.cpu[it] } +
                        CpuDetail.entries.map { row.details[it] } +
                        GpuPhase.entries.map { if (gpu) row.gpu[it] else null } +
                        listOf(
                            row.renderThreadBytes,
                            row.composeThreadBytes,
                            row.composeCalls,
                            row.recordings,
                            row.rendered,
                            row.generation,
                            row.activeItems,
                            row.cachedItems,
                            row.pendingItems,
                            row.tooltipVisible,
                            row.missingGpuResults,
                        )
                out.appendLine(values.joinToString(",") { it?.toString() ?: "" })
            }
        }
    }
}

/** Walks one schedule; the report exists only once every scheduled case recorded its result, in order. */
class BenchmarkLog(val samples: Int, val repeats: Int) {
    val schedule = BenchmarkPlan.schedule(repeats)
    private val results = mutableListOf<Map<String, Any?>>()
    val next: Pair<Int, BenchmarkCase>?
        get() = schedule.getOrNull(results.size)

    val recorded: List<Map<String, Any?>>
        get() = results

    fun record(repeat: Int, case: BenchmarkCase, result: Map<String, Any?>) {
        check(next == repeat to case) {
            "Benchmark case ${repeat + 1}-${case.name} recorded out of order; expected $next"
        }
        results += result
    }

    fun report(header: String): String {
        check(next == null) {
            "Benchmark incomplete; missing ${schedule.drop(results.size).map { "${it.first + 1}-${it.second.name}" }}"
        }
        return "PASS $header\n${results.size} measured cases (${BenchmarkPlan.cases.size} cases x $repeats repeats); " +
            "$samples measured frames per case\n"
    }

    fun failure(header: String, error: Throwable): String =
        "FAIL $header at ${next?.let { "${it.first + 1}-${it.second.name}" } ?: "REPORT"}: $error\n" +
            "${results.size}/${schedule.size} cases recorded\n\n" +
            error.stackTraceToString()
}
