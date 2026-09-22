package dev.composemc.development

import dev.composemc.neoforge.*

import com.google.gson.GsonBuilder
import dev.composemc.bridge.ComposeThread
import dev.composemc.render.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL33C.*
import java.io.File
import java.lang.management.ManagementFactory

/** Separate opt-in benchmark, with per-case warmup, complete frame rows, and bounded GPU draining. */
internal class ClientBenchmarkProbe {
    private val minecraft get() = Minecraft.getInstance()
    private val warmup = 120
    private val pipelineWarmup = 2400
    private var warmingPipeline = true
    private val samples = Integer.getInteger("composemc.benchmark.frames", 360).also { require(it in 120..1500) }
    private val repeats = Integer.getInteger("composemc.benchmark.repeats", 2).also { require(it in 1..5) }
    private val cases = listOf(
        BenchmarkCase("static-ui", BenchmarkKind.STATIC),
        BenchmarkCase("animation", BenchmarkKind.ANIMATION),
        BenchmarkCase("list-1k", BenchmarkKind.LIST, 1000),
        BenchmarkCase("list-10k", BenchmarkKind.LIST, 10000),
        BenchmarkCase("list-100k", BenchmarkKind.LIST, 100000),
        BenchmarkCase("native-static", BenchmarkKind.NATIVE_STATIC, 1000),
        BenchmarkCase("native-scroll-10k", BenchmarkKind.NATIVE_SCROLL, 10000),
        BenchmarkCase("rich-tooltip", BenchmarkKind.TOOLTIP),
    ) + dev.composemc.demo.testing.oreComponentPages.map { BenchmarkCase("ore-${it.name.lowercase()}", BenchmarkKind.ORE_COMPONENTS, it.ordinal) }
    private val schedule = (0 until repeats).flatMap { repeat ->
        (if (repeat % 2 == 0) cases else cases.reversed()).map { repeat to it }
    }
    private val results = mutableListOf<Map<String, Any?>>()
    private var environment: Map<String, Any?> = emptyMap()
    private var started = false
    private var index = 0
    private var frames = 0
    private var firstMeasured = 0L
    private var lastMeasured = 0L
    private var screen: BenchmarkScreen? = null
    private var oldGuiScale = 0
    private var oldVsync = true
    private var oldFrameLimit = 60
    private var pausedForFocus = false
    private var focusRestarts = 0
    private var measuredHiddenFrames = 0
    private var measuredFocusedFrames = 0

    fun afterRender(parent: Screen) {
        if (minecraft.overlay != null) return
        try {
            if (!started) { started = true; start(); return }
            val active = screen ?: return
            check(parent === active) { "Benchmark screen was replaced" }
            val visible = GLFW.glfwGetWindowAttrib(minecraft.window.window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE
            val physicallyFocused = GLFW.glfwGetWindowAttrib(minecraft.window.window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE
            if (BenchmarkEnvironment.background) {
                check(!visible) { "Background benchmark window became visible" }
            } else if (!minecraft.isWindowActive) { pausedForFocus = true; return }
            if (pausedForFocus) {
                pausedForFocus = false
                focusRestarts++
                println("BENCHMARK focus returned: discard and re-warm ${active.fixture.name}")
                minecraft.tell(Runnable { openCase() })
                return
            }
            check(minecraft.window.width == 1280 && minecraft.window.height == 960 && minecraft.window.guiScale == 2.0) { "Benchmark viewport changed" }
            if (!active.componentsReady) { active.advance(); return }
            frames++
            if (warmingPipeline) {
                active.advance()
                if (frames % 600 == 0) println("BENCHMARK pipeline warmup $frames/$pipelineWarmup")
                if (frames == pipelineWarmup) {
                    warmingPipeline = false
                    minecraft.tell(Runnable { openCase() })
                }
                return
            }
            if (frames > warmup && frames <= warmup + samples) {
                if (!visible) measuredHiddenFrames++
                if (physicallyFocused) measuredFocusedFrames++
            }
            val profiler = checkNotNull(active.frameProfiler)
            if (frames == 30 && active.fixture.kind == BenchmarkKind.TOOLTIP) {
                val point = ComposeThread.call { checkNotNull(active.model.tooltipTarget).center }
                active.mouseMoved(point.x.toDouble() * active.width / minecraft.window.width,
                    point.y.toDouble() * active.height / minecraft.window.height)
            }
            if (frames == warmup) firstMeasured = profiler.lastFrameId + 1
            if (frames == warmup + samples) lastMeasured = profiler.lastFrameId
            if (frames <= warmup + samples) active.advance()
            if (frames == warmup + samples + 16) finishCase(active)
        } catch (failure: Throwable) { restoreOptions(); throw failure }
    }

    private fun start() {
        oldGuiScale = minecraft.options.guiScale().get()
        oldVsync = minecraft.options.enableVsync().get()
        oldFrameLimit = minecraft.options.framerateLimit().get()
        minecraft.options.enableVsync().set(false)
        minecraft.options.framerateLimit().set(60)
        minecraft.options.guiScale().set(2)
        GLFW.glfwSetWindowSize(minecraft.window.window, 1280, 960)
        minecraft.resizeDisplay()
        if (BenchmarkEnvironment.background) {
            GLFW.glfwHideWindow(minecraft.window.window)
            GLFW.glfwPollEvents()
            check(GLFW.glfwGetWindowAttrib(minecraft.window.window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE)
        } else GLFW.glfwFocusWindow(minecraft.window.window)
        val rendererCheck = if (configuredRenderBackend() == RenderBackend.OPENGL) verifyOpenGlRenderer() else "CPU reference backend"
        environment = linkedMapOf(
            "java" to System.getProperty("java.version"), "os" to System.getProperty("os.name"),
            "osVersion" to System.getProperty("os.version"), "cpu" to System.getenv("PROCESSOR_IDENTIFIER"),
            "logicalProcessors" to Runtime.getRuntime().availableProcessors(), "gpu" to glGetString(GL_RENDERER),
            "glVersion" to glGetString(GL_VERSION), "textureUnits" to glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS),
            "backend" to configuredRenderBackend().name, "width" to 1280, "height" to 960, "guiScale" to 2,
            "windowMode" to if (BenchmarkEnvironment.background) "background-hidden" else "foreground",
            "windowIsolation" to if (BenchmarkEnvironment.background) "win32-desktop" else "none",
            "focusSource" to if (BenchmarkEnvironment.background) "benchmark-logical-focus" else "os-window",
            "vsync" to false, "frameLimit" to 60, "warmupFrames" to warmup, "measuredFrames" to samples,
            "pipelineWarmupFrames" to pipelineWarmup,
            "gpuDrainFrames" to 16, "repeats" to repeats, "repeatOrder" to "forward then reverse",
            "allocations" to java.lang.Boolean.getBoolean("composemc.allocations"),
            "rendererPreflight" to rendererCheck,
            "resourcePacks" to minecraft.resourcePackRepository.selectedPacks.map { it.id },
            "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-X") },
            "notes" to listOf("CPU spans are wall time inside Screen.render, excluding frame limiting, report export and screenshots.",
                "CPU details overlap top-level spans. GPU values are disjoint command-stream intervals, not busy time; do not add CPU and GPU times.",
                "Zero GPU requests means the stage did not run; missing requested results are null. CPU backend GPU timing is unavailable.",
                "CSV rows join CPU and delayed GPU measurements by the originating host frame ID. No glFinish or blocking query reads are used.")
        )
        minecraft.tell(Runnable { openCase() })
    }

    private fun openCase() {
        frames = 0
        measuredHiddenFrames = 0
        measuredFocusedFrames = 0
        val previous = screen
        val next = BenchmarkScreen(if (warmingPipeline) BenchmarkCase("pipeline-warmup", BenchmarkKind.NATIVE_SCROLL, 10000) else schedule[index].second)
        screen = next
        minecraft.setScreen(next)
        if (previous != null) check(previous.rendererStatistics.liveSurfaces == 0 && previous.rendererStatistics.liveNativeImages == 0) {
            "Benchmark case leaked renderer resources"
        }
    }

    private fun finishCase(active: BenchmarkScreen) {
        active.verifyComponents()
        val rows = active.frameProfiler!!.frames().filter { it.frameId in firstMeasured..lastMeasured }
        check(rows.size == samples)
        check(rows.all { it.missingGpuResults == 0 }) { "GPU results did not drain; benchmark is incomplete" }
        check(rows.maxOf { it.recordings } <= 2)
        check(rows.maxOf { it.cachedItems } <= 128)
        if (active.fixture.kind == BenchmarkKind.STATIC || active.fixture.kind == BenchmarkKind.NATIVE_STATIC)
            check(rows.none { it.rendered }) { "Static fixture kept repainting" }
        if (active.fixture.kind == BenchmarkKind.LIST || active.fixture.kind == BenchmarkKind.ANIMATION)
            check(rows.count { it.rendered } > samples * 9 / 10) { "Animated/scroll fixture did not advance" }
        if (active.fixture.kind == BenchmarkKind.TOOLTIP) check(rows.all { it.tooltipVisible }) {
            "Tooltip benchmark lost hover: visible=${rows.count { it.tooltipVisible }}/${rows.size}, " +
                "target=${ComposeThread.call { active.model.tooltipTarget }}, current=${active.nativeTooltipStatistics}"
        }
        val (repeat, fixture) = schedule[index]
        val stem = "${repeat + 1}-${fixture.name}"
        val directory = File(minecraft.gameDirectory, "benchmark-results").also { it.mkdirs() }
        writeCsv(File(directory, "$stem.csv"), rows, active.renderBackend == RenderBackend.OPENGL)
        Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { image ->
            image.writeToFile(File(directory, "$stem.png"))
            val colors = HashSet<Int>()
            for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) colors += image.getPixelRGBA(x, y)
            check(colors.size > 16) { "Benchmark framebuffer is blank: $stem" }
        }
        val metrics = linkedMapOf<String, Any?>("total_cpu_wall_ns" to summary(rows.map { it.totalNanos }),
            "frame_interval_ns" to summary(rows.zipWithNext { a, b -> b.startedNanos - a.startedNanos }))
        CpuPhase.entries.forEach { phase -> metrics["cpu_${phase.name.lowercase()}_ns"] = summary(rows.map { it.cpu.getValue(phase) }) }
        CpuDetail.entries.forEach { phase -> metrics["detail_${phase.name.lowercase()}_ns"] = summary(rows.map { it.details.getValue(phase) }) }
        if (active.renderBackend == RenderBackend.OPENGL) {
            GpuPhase.entries.forEach { phase -> metrics["gpu_${phase.name.lowercase()}_ns"] = summary(rows.map { it.gpu.getValue(phase)!! }) }
            metrics["gpu_stage_sum_ns"] = summary(rows.map { it.gpu.values.sumOf { value -> checkNotNull(value) } })
        }
        metrics["render_thread_bytes"] = summary(rows.mapNotNull { it.renderThreadBytes })
        metrics["compose_thread_bytes"] = summary(rows.mapNotNull { it.composeThreadBytes })
        metrics["compose_calls"] = summary(rows.map { it.composeCalls.toLong() })
        results += linkedMapOf("name" to fixture.name, "dataCount" to fixture.count, "repeat" to repeat + 1,
            "frames" to rows.size, "redraws" to rows.count { it.rendered }, "recordings" to rows.sumOf { it.recordings },
            "hiddenFrames" to measuredHiddenFrames, "windowFocusedFrames" to measuredFocusedFrames,
            "gpuOperations" to GpuPhase.entries.associateWith { phase -> rows.sumOf { it.gpuRequests.getValue(phase) } },
            "maxActiveItems" to rows.maxOf { it.activeItems }, "maxCachedItems" to rows.maxOf { it.cachedItems },
            "framesWithPendingItems" to rows.count { it.pendingItems > 0 }, "metrics" to metrics,
            "renderer" to active.rendererStatistics, "nativeItems" to active.nativeItemStatistics,
            "nativeTooltips" to active.nativeTooltipStatistics)
        File(directory, "report.json").writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(
            linkedMapOf("environment" to environment, "discardedFocusSegments" to focusRestarts, "results" to results)))
        println("BENCHMARK $stem: CPU wall ${summary(rows.map { it.totalNanos })}, redraws=${rows.count { it.rendered }}")
        index++
        if (index == schedule.size) {
            minecraft.setScreen(null)
            check(active.rendererStatistics.liveSurfaces == 0 && active.rendererStatistics.liveNativeImages == 0)
            screen = null
            restoreOptions()
            File(minecraft.gameDirectory, "composemc-benchmark.txt").writeText("PASS\n${results.size} cases; $samples measured frames per case\n")
            minecraft.stop()
        } else minecraft.tell(Runnable { openCase() })
    }

    private fun summary(values: List<Long>): Map<String, Any>? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun percentile(p: Int) = sorted[((sorted.size * p + 99) / 100 - 1).coerceAtLeast(0)]
        return linkedMapOf("samples" to values.size, "nonzero" to values.count { it != 0L },
            "p50" to percentile(50), "p95" to percentile(95), "p99" to percentile(99), "mean" to values.average())
    }

    private fun writeCsv(file: File, rows: List<UiFrameProfile>, gpuAvailable: Boolean) {
        file.bufferedWriter().use { out ->
            out.appendLine((listOf("frame_id", "start_relative_ns", "total_cpu_wall_ns") + CpuPhase.entries.map { "cpu_${it.name.lowercase()}_ns" } +
                CpuDetail.entries.map { "detail_${it.name.lowercase()}_ns" } + GpuPhase.entries.map { "gpu_${it.name.lowercase()}_ns" } +
                listOf("render_bytes", "compose_bytes", "compose_calls", "recordings", "rendered", "generation", "active_items", "cached_items", "pending_items", "tooltip_visible", "missing_gpu")).joinToString(","))
            rows.forEach { row ->
                val values = listOf<Any?>(row.frameId, row.startedNanos - rows.first().startedNanos, row.totalNanos) +
                    CpuPhase.entries.map { row.cpu[it] } + CpuDetail.entries.map { row.details[it] } +
                    GpuPhase.entries.map { if (gpuAvailable) row.gpu[it] else null } + listOf(row.renderThreadBytes, row.composeThreadBytes,
                    row.composeCalls, row.recordings, row.rendered, row.generation, row.activeItems, row.cachedItems, row.pendingItems, row.tooltipVisible, row.missingGpuResults)
                out.appendLine(values.joinToString(",") { it?.toString() ?: "" })
            }
        }
    }

    private fun restoreOptions() {
        screen = null
        minecraft.options.guiScale().set(oldGuiScale)
        minecraft.options.enableVsync().set(oldVsync)
        minecraft.options.framerateLimit().set(oldFrameLimit)
    }
}
