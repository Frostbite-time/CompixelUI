package dev.compixel.development

import dev.compixel.bridge.ComposeThread
import dev.compixel.testing.suite.SuitePixels
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/** Real Compose hit testing and native rich components, advanced once per acceptance tick. */
internal class NativeTooltipProbe(private val session: SuiteSession) : AutoCloseable {
    private val minecraft
        get() = Minecraft.getInstance()

    private var stage = 0
    private var since = System.nanoTime()
    private var cancel = false
    private val cancellation = SuitePlatform.cancelTooltips { cancel }
    private var reload: CompletableFuture<Void>? = null
    private var reloadReady = false
    private var reloadHovered = false
    private var allocationsBeforeReload = 0L
    private var preparedBeforeScroll = 0L
    private var visibleTicks = 0
    private var closed = false

    fun advance(screen: ComposePreviewScreen): Boolean {
        val now = System.nanoTime()
        val elapsed = (now - since) / 1_000_000
        fun next() {
            stage++
            since = now
            visibleTicks = 0
        }
        fun visible(): Boolean = screen.nativeTooltipStatistics.visible && screen.nativeTooltipBounds?.isEmpty == false
        // Shown on two consecutive ticks, so the last completed frame that a capture reads contains it.
        fun shown(): Boolean {
            visibleTicks = if (visible()) visibleTicks + 1 else 0
            return visibleTicks >= 2
        }
        fun waitFor(condition: Boolean, timeoutMillis: Long = 8_000): Boolean {
            check(condition || elapsed < timeoutMillis) {
                "Tooltip stage $stage timed out: ${screen.nativeTooltipStatistics}, bounds=${screen.nativeTooltipBounds}"
            }
            return condition
        }
        when (stage) {
            0 -> {
                moveToCell(screen, 1)
                next()
            }
            1 ->
                if (elapsed >= 200) {
                    check(!visible()) { "Tooltip appeared before its hover delay" }
                    next()
                }
            2 ->
                if (waitFor(shown())) {
                    check(screen.nativeTooltipStatistics.components > 1) { "Sword tooltip lost native attribute text" }
                    capture(screen, "sword")
                    moveToCell(screen, 8)
                    next()
                }
            3 ->
                if (elapsed >= 200) {
                    check(!visible()) { "Previous item's tooltip survived a new hover target" }
                    next()
                }
            4 ->
                if (waitFor(shown())) {
                    check(screen.nativeTooltipStatistics.richComponents > 0) {
                        "Bundle tooltip lost its image component"
                    }
                    capture(screen, "bundle")
                    cancel = true
                    next()
                }
            5 ->
                if (elapsed >= 250) {
                    check(!visible()) { "Native tooltip cancellation was ignored" }
                    cancel = false
                    next()
                }
            6 ->
                if (waitFor(shown())) {
                    val point = cellPoint(screen, 8)
                    screen.mouseClicked(point.first, point.second, SuitePlatform.MOUSE_LEFT)
                    screen.mouseReleased(point.first, point.second, SuitePlatform.MOUSE_LEFT)
                    next()
                }
            7 ->
                if (elapsed >= 180) {
                    check(!visible()) { "Tooltip survived a click" }
                    check(ComposeThread.call { screen.itemBrowser.selected } == 8) {
                        "Tooltip swallowed the item click"
                    }
                    screen.mouseMoved(-10.0, -10.0)
                    next()
                }
            8 ->
                if (elapsed >= 100) {
                    moveToCell(screen, 1)
                    next()
                }
            9 ->
                if (elapsed >= 150) {
                    screen.session!!.post(Runnable { screen.model.dialog = true })
                    next()
                }
            10 ->
                if (elapsed >= 800) {
                    check(ComposeThread.call { screen.model.dialog } && !visible()) {
                        "Delayed tooltip appeared over a modal"
                    }
                    capture(screen, "modal", requireTooltip = false)
                    dismissDialog(screen)
                    next()
                }
            11 ->
                if (elapsed >= 150) {
                    moveToCell(screen, 1)
                    next()
                }
            12 ->
                if (waitFor(shown())) {
                    screen.session!!.post(Runnable { screen.model.dialog = true })
                    next()
                }
            13 ->
                if (elapsed >= 250) {
                    check(!visible()) { "Visible tooltip survived a modal opening" }
                    dismissDialog(screen)
                    next()
                }
            14 ->
                if (elapsed >= 150) {
                    moveToEdge(screen)
                    next()
                }
            15 ->
                if (waitFor(shown())) {
                    check(screen.nativeTooltipStatistics.richComponents > 0) { "Edge tooltip lost its image component" }
                    capture(screen, "edge")
                    allocationsBeforeReload = screen.rendererStatistics.surfaceAllocations
                    reload = minecraft.reloadResourcePacks()
                    next()
                }
            16 -> {
                if (!reloadReady) {
                    if (waitFor(reload?.isDone == true, timeoutMillis = 120_000)) {
                        reload!!.join()
                        reloadReady = true
                        // The loading overlay can reset hover. Re-enter using real Screen input.
                        screen.mouseMoved(-10.0, -10.0)
                        since = now
                    }
                } else if (!reloadHovered) {
                    if (elapsed >= 150) {
                        moveToEdge(screen)
                        reloadHovered = true
                        since = now
                    }
                } else if (waitFor(shown())) {
                    check(screen.rendererStatistics.surfaceAllocations == allocationsBeforeReload + 1) {
                        "Resource reload did not rebuild exactly one surface: ${screen.rendererStatistics}"
                    }
                    capture(screen, "reloaded")
                    SuitePlatform.setGuiScale(3)
                    next()
                }
            }
            17 ->
                if (elapsed >= 300) {
                    moveToEdge(screen)
                    next()
                }
            18 ->
                if (waitFor(shown())) {
                    check(SuitePlatform.guiScale == 3) { "GUI scale 3 was not applied" }
                    capture(screen, "scale3")
                    SuitePlatform.setGuiScale(2)
                    screen.mouseMoved(-10.0, -10.0)
                    next()
                }
            19 ->
                if (elapsed >= 300) {
                    moveToEdge(screen)
                    next()
                }
            20 ->
                if (waitFor(shown())) {
                    preparedBeforeScroll = screen.nativeTooltipStatistics.preparedImages
                    val point = edgePoint(screen)
                    screen.mouseScrolled(point.first, point.second, 0.0, -1.0)
                    next()
                }
            21 ->
                if (elapsed >= 180) {
                    check(!visible()) { "Tooltip survived wheel scrolling" }
                    screen.mouseMoved(-10.0, -10.0)
                    next()
                }
            22 ->
                if (elapsed >= 300) {
                    check(!visible()) { "Tooltip reappeared without hover" }
                    check(screen.nativeTooltipStatistics.preparedImages == preparedBeforeScroll) {
                        "Hidden tooltip still prepares images"
                    }
                    close()
                    return true
                }
        }
        return false
    }

    private fun cellPoint(screen: ComposePreviewScreen, index: Int): Pair<Double, Double> {
        val bounds = ComposeThread.call {
            checkNotNull(screen.itemBrowser.visibleCells[index]) { "Item cell $index is not laid out" }
        }
        return toGui(screen, bounds.center.x, bounds.top + 30f)
    }

    private fun edgePoint(screen: ComposePreviewScreen): Pair<Double, Double> {
        val point = ComposeThread.call {
            checkNotNull(screen.itemBrowser.edgeTooltipBounds) { "Edge item is not laid out" }.center
        }
        return toGui(screen, point.x, point.y)
    }

    private fun toGui(screen: ComposePreviewScreen, x: Float, y: Float) =
        x.toDouble() * screen.width / minecraft.window.width to y.toDouble() * screen.height / minecraft.window.height

    private fun moveToCell(screen: ComposePreviewScreen, index: Int) {
        val point = cellPoint(screen, index)
        screen.mouseMoved(point.first, point.second)
    }

    private fun moveToEdge(screen: ComposePreviewScreen) {
        val point = edgePoint(screen)
        screen.mouseMoved(point.first, point.second)
    }

    private fun dismissDialog(screen: ComposePreviewScreen) {
        screen.keyPressed(SuitePlatform.KEY_ESCAPE, 0, 0)
        screen.keyReleased(SuitePlatform.KEY_ESCAPE, 0, 0)
        check(SuitePlatform.screen === screen && !ComposeThread.call { screen.model.dialog }) {
            "Escape did not close only the dialog"
        }
        screen.mouseMoved(-10.0, -10.0)
    }

    private fun capture(screen: ComposePreviewScreen, name: String, requireTooltip: Boolean = true) {
        val bounds =
            if (requireTooltip) checkNotNull(screen.nativeTooltipBounds) { "Tooltip $name has no bounds" } else null
        if (bounds != null) {
            check(
                bounds.left >= 0 &&
                    bounds.top >= 0 &&
                    bounds.right <= minecraft.window.width &&
                    bounds.bottom <= minecraft.window.height
            ) {
                "Tooltip crossed the framebuffer edge: $bounds"
            }
            check(bounds.width >= 16 && bounds.height >= 16) { "Tooltip $name is too small: $bounds" }
        }
        session.capture("tooltip-$name") { pixels ->
            SuitePixels.requireContent(pixels, "tooltip-$name")
            if (bounds != null)
                SuitePixels.requireTooltipImage(
                    pixels,
                    "tooltip-$name",
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                )
        }
    }

    override fun close() {
        if (!closed) {
            cancellation.close()
            closed = true
        }
    }
}
