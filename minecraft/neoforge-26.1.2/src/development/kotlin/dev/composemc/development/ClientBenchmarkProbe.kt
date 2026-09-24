package dev.composemc.development

import com.google.gson.GsonBuilder
import com.mojang.logging.LogUtils
import dev.composemc.bridge.ComposeThread
import dev.composemc.development.render.verifyRenderer
import dev.composemc.render.GpuPhase
import dev.composemc.testing.suite.BenchmarkKind
import dev.composemc.testing.suite.BenchmarkLog
import dev.composemc.testing.suite.BenchmarkPlan
import dev.composemc.testing.suite.BenchmarkRecords
import dev.composemc.testing.suite.BenchmarkValidity
import dev.composemc.testing.suite.ClientSuite
import dev.composemc.testing.suite.SuitePixels
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/**
 * Performance suite: [BenchmarkPlan] in a fresh flat creative world. Each case gets its own warmup,
 * frame-exact samples joined with delayed GPU results, and a bounded GPU drain. Focus is logical.
 */
internal class ClientBenchmarkProbe {
    private val minecraft get() = Minecraft.getInstance()
    private val session = SuiteSession(ClientSuite.BENCHMARK, BenchmarkPlan.FRAME_LIMIT)
    private val log = BenchmarkLog(BenchmarkPlan.samples(), BenchmarkPlan.repeats())
    private var environment: Map<String, Any?> = emptyMap()
    private var started = false
    private var world = false
    private var finished = false
    private var warmingPipeline = true
    private var screen: BenchmarkScreen? = null
    private var switching = false
    private var capturing = false
    private var frames = 0
    private var firstMeasured = 0L
    private var lastMeasured = 0L
    private var refreshesAtSampleStart = 0L
    private var hiddenFrames = 0
    private var focusedFrames = 0
    private var reload: CompletableFuture<Void>? = null
    private var framesAfterReload = 0

    fun afterRender(parent: Screen) = guard {
        if (!started) {
            if (!SuitePlatform.overlayActive) start(parent)
            return@guard
        }
        session.checkWindow()
        if (!session.rendered(parent) || switching) return@guard
        if (SuiteEnvironment.control) controlFrame() else screen?.let(::measure)
    }

    fun tick() = guard {
        if (!started || world) return@guard
        if (session.worldReady) {
            world = true
            if (SuiteEnvironment.control) {
                session.open(SuiteParentScreen())
                reload = minecraft.reloadResourcePacks()
            } else openCase()
        }
    }

    private fun start(parent: Screen) {
        started = true
        session.prepare()
        // A control run starts no Compose or Skia renderer at all, including the preflight.
        val rendererCheck = if (SuiteEnvironment.control) null else verifyRenderer()
        environment = linkedMapOf(
            "suite" to ClientSuite.BENCHMARK.id, "label" to System.getProperty("composemc.benchmark.label", "baseline"),
            "minecraft" to SuitePlatform.MINECRAFT, "loader" to SuitePlatform.LOADER,
            "java" to System.getProperty("java.version"), "os" to System.getProperty("os.name"),
            "osVersion" to System.getProperty("os.version"), "cpu" to System.getenv("PROCESSOR_IDENTIFIER"),
            "logicalProcessors" to Runtime.getRuntime().availableProcessors(), "device" to SuitePlatform.device(),
            "backend" to SuitePlatform.backend.name, "width" to BenchmarkPlan.WIDTH, "height" to BenchmarkPlan.HEIGHT,
            "guiScale" to BenchmarkPlan.GUI_SCALE,
            "windowMode" to if (SuiteEnvironment.background) "background-hidden" else "foreground",
            "windowIsolation" to if (java.lang.Boolean.getBoolean("composemc.suite.isolated")) "win32-desktop" else "none",
            "focusSource" to "suite-logical-focus", "world" to "fresh flat creative, render distance 2",
            "control" to SuiteEnvironment.control, "vsync" to false, "frameLimit" to BenchmarkPlan.FRAME_LIMIT,
            "warmupFrames" to BenchmarkPlan.WARMUP_FRAMES, "measuredFrames" to log.samples,
            "pipelineWarmupFrames" to BenchmarkPlan.PIPELINE_WARMUP_FRAMES, "gpuDrainFrames" to BenchmarkPlan.GPU_DRAIN_FRAMES,
            "repeats" to log.repeats, "repeatOrder" to "forward then reverse",
            "allocations" to java.lang.Boolean.getBoolean("composemc.allocations"), "rendererPreflight" to rendererCheck,
            "resourcePacks" to SuitePlatform.resourcePacks(),
            "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-X") },
            "notes" to BenchmarkPlan.notes,
        )
        writeReport()
        session.createWorld(parent)
    }

    private fun controlFrame() {
        val pending = reload ?: return
        if (!pending.isDone) return
        if (++framesAfterReload < 120) return
        pending.join()
        finished = true
        session.finish("PASS ${session.header} control: no Compose renderer created\n")
    }

    private fun openCase() {
        switching = false
        val case = if (warmingPipeline) BenchmarkPlan.pipelineWarmup else checkNotNull(log.next).second
        val next = BenchmarkScreen(case)
        session.open(next)
        screen = next
        frames = 0
        hiddenFrames = 0
        focusedFrames = 0
    }

    /** Screen changes wait for the task queue, outside the frame that finished the previous case. */
    private fun later(block: () -> Unit) {
        switching = true
        SuitePlatform.defer { guard(block) }
    }

    private fun measure(active: BenchmarkScreen) {
        check(minecraft.window.width == BenchmarkPlan.WIDTH && minecraft.window.height == BenchmarkPlan.HEIGHT &&
            SuitePlatform.guiScale == BenchmarkPlan.GUI_SCALE) { "Benchmark viewport changed" }
        if (capturing) {
            if (session.capturesIdle) {
                capturing = false
                later { if (log.next == null) finish() else openCase() }
            }
            return
        }
        if (!active.componentsReady) { active.advance(); return }
        frames++
        if (warmingPipeline) {
            active.advance()
            if (frames % 600 == 0) LOGGER.info("BENCHMARK pipeline warmup {}/{}", frames, BenchmarkPlan.PIPELINE_WARMUP_FRAMES)
            if (frames == BenchmarkPlan.PIPELINE_WARMUP_FRAMES) {
                warmingPipeline = false
                later(::openCase)
            }
            return
        }
        val warmup = BenchmarkPlan.WARMUP_FRAMES
        val samples = log.samples
        val profiler = checkNotNull(active.frameProfiler) { "Benchmark screens need -Dcomposemc.profile=true" }
        if (frames > warmup && frames <= warmup + samples) {
            if (SuitePlatform.windowHidden) hiddenFrames++
            if (SuitePlatform.windowFocused) focusedFrames++
        }
        if (frames == BenchmarkPlan.TOOLTIP_HOVER_FRAME && active.fixture.kind == BenchmarkKind.TOOLTIP) {
            val point = ComposeThread.call { checkNotNull(active.model.tooltipTarget) { "The tooltip target is not laid out" }.center }
            active.mouseMoved(point.x.toDouble() * active.width / minecraft.window.width,
                point.y.toDouble() * active.height / minecraft.window.height)
        }
        if (frames == warmup) {
            firstMeasured = profiler.lastFrameId + 1
            refreshesAtSampleStart = active.nativeItemStatistics.animationRefreshes
        }
        if (frames == warmup + samples) lastMeasured = profiler.lastFrameId
        if (frames <= warmup + samples) active.advance()
        if (frames == warmup + samples + BenchmarkPlan.GPU_DRAIN_FRAMES) finishCase(active)
    }

    private fun finishCase(active: BenchmarkScreen) {
        val (repeat, case) = checkNotNull(log.next)
        check(active.fixture == case) { "Benchmark screen ${active.fixture.name} does not match the schedule's ${case.name}" }
        active.verifyComponents()
        val rows = checkNotNull(active.frameProfiler).frames().filter { it.frameId in firstMeasured..lastMeasured }
        val items = active.nativeItemStatistics
        BenchmarkValidity.check(case, rows, log.samples, items.activeVariants, items.dynamicVariants,
            items.animationRefreshes - refreshesAtSampleStart)
        val stem = "${repeat + 1}-${case.name}"
        BenchmarkRecords.writeCsv(File(session.output, "$stem.csv"), rows)
        val metrics = BenchmarkRecords.metrics(rows)
        log.record(repeat, case, linkedMapOf("name" to case.name, "dataCount" to case.count, "repeat" to repeat + 1,
            "frames" to rows.size, "redraws" to rows.count { it.rendered }, "recordings" to rows.sumOf { it.recordings },
            "hiddenFrames" to hiddenFrames, "windowFocusedFrames" to focusedFrames,
            "gpuTimed" to BenchmarkRecords.gpuTimed(rows),
            "gpuOperations" to GpuPhase.entries.associateWith { phase -> rows.sumOf { it.gpuRequests.getValue(phase) } },
            "maxActiveItems" to rows.maxOf { it.activeItems }, "maxCachedItems" to rows.maxOf { it.cachedItems },
            "framesWithPendingItems" to rows.count { it.pendingItems > 0 }, "metrics" to metrics,
            "renderer" to active.rendererStatistics, "nativeItems" to items, "nativeTooltips" to active.nativeTooltipStatistics))
        writeReport()
        LOGGER.info("BENCHMARK {}: CPU wall {}, redraws={}", stem, metrics["total_cpu_wall_ns"], rows.count { it.rendered })
        capturing = true
        session.capture(stem) { SuitePixels.requireContent(it, stem) }
    }

    private fun finish() {
        finished = true
        writeReport()
        session.open(SuiteParentScreen())
        session.finish(log.report(session.header))
    }

    private fun writeReport() {
        File(session.output, "report.json").writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(
            linkedMapOf("environment" to environment, "results" to log.recorded)))
    }

    private inline fun guard(block: () -> Unit) {
        if (finished) return
        try {
            block()
            session.rethrowCaptureFailure()
        } catch (failure: Throwable) {
            finished = true
            session.fail(log.failure(session.header, failure), failure)
        }
    }

    private companion object { val LOGGER = LogUtils.getLogger() }
}
