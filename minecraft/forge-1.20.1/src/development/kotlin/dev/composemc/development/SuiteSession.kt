package dev.composemc.development

import com.mojang.logging.LogUtils
import dev.composemc.testing.suite.BenchmarkPlan
import dev.composemc.testing.suite.ClientSuite
import dev.composemc.testing.suite.ScreenPixels
import java.io.File
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/** Neutral vanilla parent for suite screens. Unlike vanilla menus it does not pause the integrated server. */
internal class SuiteParentScreen : Screen(Component.literal("Compose MC suite")) {
    override fun isPauseScreen(): Boolean = false
}

/** Client state shared by both suites: pinned options, the hidden window, the test world, screens and captures. */
internal class SuiteSession(val suite: ClientSuite, private val frameLimit: Int) {
    private val minecraft
        get() = Minecraft.getInstance()

    val output = File(minecraft.gameDirectory, suite.results)
    val header
        get() =
            "${SuitePlatform.MINECRAFT} ${SuitePlatform.LOADER} ${SuitePlatform.backend.name.lowercase()}" +
                if (SuiteEnvironment.background) " hidden" else " visible"

    private var restoreOptions: (() -> Unit)? = null
    private var worldRequestedAt = 0L
    private var pendingCaptures = 0
    private var captureFailure: Throwable? = null
    private var finished = false
    /**
     * The suite screen whose rendered frames drive the suite, or null over the game view. Other screens are ignored.
     */
    var current: Screen? = null
        private set

    /** Frames rendered by [current] since it was adopted. */
    var frames = 0
        private set

    val capturesIdle
        get() = pendingCaptures == 0

    /** Pins every option that changes pacing, layout or background work, then hides the window when requested. */
    fun prepare() {
        output.deleteRecursively()
        check(output.mkdirs()) { "Cannot create $output" }
        restoreOptions =
            SuitePlatform.prepareOptions(frameLimit, BenchmarkPlan.WIDTH, BenchmarkPlan.HEIGHT, BenchmarkPlan.GUI_SCALE)
        if (SuiteEnvironment.background) SuitePlatform.hideWindow()
        checkWindow()
    }

    fun checkWindow() {
        if (SuiteEnvironment.background) check(SuitePlatform.windowHidden) { "Background suite window became visible" }
    }

    /** Replaces any previous save of this suite with a fresh flat creative world. */
    fun createWorld(parent: Screen) {
        worldRequestedAt = System.nanoTime()
        SuitePlatform.defer { SuitePlatform.createFlatWorld("composemc-${suite.id}", parent) }
    }

    val worldReady: Boolean
        get() {
            check(worldRequestedAt != 0L) { "The test world was never requested" }
            val ready =
                minecraft.level != null &&
                    minecraft.player != null &&
                    minecraft.singleplayerServer != null &&
                    !SuitePlatform.overlayActive &&
                    SuitePlatform.screen == null
            if (!ready)
                check(System.nanoTime() - worldRequestedAt < 180_000_000_000L) {
                    "The test world did not load within 180 s"
                }
            return ready
        }

    /** Opens [next] outside rendering and verifies that a replaced Compose screen released everything. */
    fun open(next: Screen) {
        val previous = current
        SuitePlatform.setScreen(next)
        check(SuitePlatform.screen === next) { "${next.title.string} did not open" }
        adopt(next)
        if (previous is SuiteComposeScreen && previous !== next) requireReleased(previous)
    }

    /** Returns to the game view and verifies that a replaced Compose screen released everything. */
    fun closeScreen() {
        val previous = current
        SuitePlatform.setScreen(null)
        check(SuitePlatform.screen == null) { "The game view did not return" }
        current = null
        frames = 0
        if (previous is SuiteComposeScreen) requireReleased(previous)
    }

    /** Follows a screen that Minecraft or a key mapping opened. */
    fun adopt(screen: Screen) {
        current = screen
        frames = 0
    }

    /** Called after every screen render; returns whether the frame belonged to the suite screen. */
    fun rendered(screen: Screen): Boolean {
        SuitePlatform.clearToasts()
        if (screen !== current || SuitePlatform.screen !== screen) return false
        frames++
        return true
    }

    fun requireReleased(screen: SuiteComposeScreen) {
        val name = screen.title.string
        val renderer = screen.rendererStatistics
        val items = screen.nativeItemStatistics
        val tooltips = screen.nativeTooltipStatistics
        check(screen.session == null) { "$name kept its Compose session after closing" }
        check(renderer.liveSurfaces == 0 && renderer.liveNativeImages == 0) {
            "$name leaked renderer resources: $renderer"
        }
        check(items.preparedImages == items.retiredImages) { "$name leaked native item images: $items" }
        check(tooltips.preparedImages == tooltips.retiredImages) { "$name leaked native tooltip images: $tooltips" }
    }

    /** Captures the last completed frame. Checks may run later; their failure surfaces on the next suite call. */
    fun capture(name: String, check: (ScreenPixels) -> Unit) {
        pendingCaptures++
        SuitePlatform.screenshot(File(output, "$name.png")) { pixels ->
            pendingCaptures--
            try {
                check(pixels)
            } catch (failure: Throwable) {
                if (captureFailure == null) captureFailure = failure
            }
        }
    }

    fun rethrowCaptureFailure() {
        captureFailure?.let { throw it }
    }

    fun finish(report: String) {
        if (finished) return
        finished = true
        restore()
        write(report)
        LOGGER.info("Compose MC {} suite finished:\n{}", suite.id, report)
        minecraft.stop()
    }

    fun fail(report: String, error: Throwable) {
        if (finished) return
        finished = true
        LOGGER.error("Compose MC {} suite failed", suite.id, error)
        try {
            restore()
        } catch (restoreFailure: Throwable) {
            error.addSuppressed(restoreFailure)
        }
        write(report)
        minecraft.stop()
    }

    private fun restore() {
        SuiteEnvironment.focused = true
        restoreOptions?.invoke()
        restoreOptions = null
    }

    private fun write(report: String) = File(minecraft.gameDirectory, suite.report).writeText(report)

    private companion object {
        val LOGGER = LogUtils.getLogger()
    }
}

/**
 * A tick-driven sequence: one operation completes per client tick, and only after the suite screen rendered a frame
 * since the previous operation, so every input is seen by a real frame.
 */
internal class SuiteScript(private val session: SuiteSession) {
    private class Operation(
        val description: String,
        val minimumNanos: Long,
        val timeoutNanos: Long,
        val poll: () -> Boolean,
    )

    private val operations = ArrayDeque<Operation>()
    private var startedAt = 0L
    private var screenAtLastOperation: Screen? = null
    private var framesAtLastOperation = 0
    val done
        get() = operations.isEmpty()

    fun act(description: String, block: () -> Unit) {
        operations +=
            Operation(description, 0L, FRAME_TIMEOUT) {
                block()
                true
            }
    }

    fun until(description: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        operations += Operation(description, 0L, timeoutMillis * 1_000_000L, condition)
    }

    fun pause(millis: Long) {
        operations += Operation("pause $millis ms", millis * 1_000_000L, millis * 1_000_000L + FRAME_TIMEOUT) { true }
    }

    fun advance(now: Long = System.nanoTime()) {
        val operation = operations.firstOrNull() ?: return
        if (startedAt == 0L) startedAt = now
        val elapsed = now - startedAt
        val baseline = if (session.current === screenAtLastOperation) framesAtLastOperation else 0
        val rendered = session.current == null || session.frames > baseline
        if (rendered && elapsed >= operation.minimumNanos && operation.poll()) {
            operations.removeFirst()
            startedAt = 0L
            screenAtLastOperation = session.current
            framesAtLastOperation = session.frames
            return
        }
        check(elapsed < operation.timeoutNanos) {
            (if (rendered) "Timed out after ${elapsed / 1_000_000} ms waiting for: "
            else "No suite frame rendered for ${elapsed / 1_000_000} ms before: ") + operation.description
        }
    }

    private companion object {
        const val FRAME_TIMEOUT = 30_000_000_000L
    }
}
