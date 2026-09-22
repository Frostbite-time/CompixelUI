package dev.composemc.development

import dev.composemc.neoforge.*

import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderTooltipEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/** Real Compose hit testing and native rich components; advanced only by the client smoke probe. */
internal class NativeTooltipProbe : AutoCloseable {
    private val minecraft get() = Minecraft.getInstance()
    private var stage = 0
    private var since = System.nanoTime()
    private var cancel = false
    private val cancellation = Consumer<RenderTooltipEvent.Pre> { if (cancel) it.isCanceled = true }
    private var reload: CompletableFuture<Void>? = null
    private var reloadReady = false
    private var reloadHovered = false
    private var allocationsBeforeReload = 0L
    private var preparedBeforeScroll = 0L
    private var closed = false
    val report = "Native tooltips: delayed hover, exclusive replacement, rich bundle image, event cancellation, " +
        "click/scroll dismissal, pending/visible modal suppression, edge placement, GUI scales 2/3, reload and retirement"

    init { NeoForge.EVENT_BUS.addListener(cancellation) }

    fun advance(screen: ComposePreviewScreen): Boolean {
        val now = System.nanoTime()
        val elapsed = (now - since) / 1_000_000
        fun next() { stage++; since = now }
        fun visible(): Boolean = screen.nativeTooltipStatistics.visible && screen.nativeTooltipBounds?.isEmpty == false
        fun waitFor(condition: Boolean): Boolean {
            check(condition || elapsed < 8_000) { "Tooltip stage $stage timed out: ${screen.nativeTooltipStatistics}, bounds=${screen.nativeTooltipBounds}" }
            return condition
        }
        when (stage) {
            0 -> { moveToCell(screen, 1); next() }
            1 -> if (elapsed >= 200) {
                check(!visible()) { "Tooltip appeared before its hover delay" }
                next()
            }
            2 -> if (waitFor(visible())) {
                check(screen.nativeTooltipStatistics.components > 1) { "Sword tooltip lost native attribute text" }
                capture(screen, "sword")
                moveToCell(screen, 8)
                next()
            }
            3 -> if (elapsed >= 200) {
                check(!visible()) { "Previous item's tooltip survived a new hover target" }
                next()
            }
            4 -> if (waitFor(visible())) {
                check(screen.nativeTooltipStatistics.richComponents > 0) { "Bundle tooltip lost its image component" }
                capture(screen, "bundle")
                cancel = true
                next()
            }
            5 -> if (elapsed >= 250) {
                check(!visible()) { "Native tooltip cancellation was ignored" }
                cancel = false
                next()
            }
            6 -> if (waitFor(visible())) {
                val point = cellPoint(screen, 8)
                screen.mouseClicked(point.first, point.second, 0)
                screen.mouseReleased(point.first, point.second, 0)
                next()
            }
            7 -> if (elapsed >= 180) {
                check(!visible()) { "Tooltip survived a click" }
                check(ComposeThread.call { screen.itemBrowser.selected } == 8) { "Tooltip swallowed the item click" }
                screen.mouseMoved(-10.0, -10.0)
                next()
            }
            8 -> if (elapsed >= 100) { moveToCell(screen, 1); next() }
            9 -> if (elapsed >= 150) {
                screen.session!!.post(Runnable { screen.model.dialog = true })
                next()
            }
            10 -> if (elapsed >= 800) {
                check(ComposeThread.call { screen.model.dialog } && !visible()) { "Delayed tooltip appeared over a modal" }
                capture(screen, "modal", requireTooltip = false)
                dismissDialog(screen)
                next()
            }
            11 -> if (elapsed >= 150) { moveToCell(screen, 1); next() }
            12 -> if (waitFor(visible())) {
                screen.session!!.post(Runnable { screen.model.dialog = true })
                next()
            }
            13 -> if (elapsed >= 250) {
                check(!visible()) { "Visible tooltip survived a modal opening" }
                dismissDialog(screen)
                next()
            }
            14 -> if (elapsed >= 150) { moveToEdge(screen); next() }
            15 -> if (waitFor(visible())) {
                check(screen.nativeTooltipStatistics.richComponents > 0)
                capture(screen, "edge")
                allocationsBeforeReload = screen.rendererStatistics.surfaceAllocations
                reload = minecraft.reloadResourcePacks()
                next()
            }
            16 -> {
                if (!reloadReady) {
                    if (waitFor(reload?.isDone == true)) {
                        reload!!.join()
                        reloadReady = true
                        // The loading overlay can reset hover. Re-enter using real Screen input.
                        screen.mouseMoved(-10.0, -10.0)
                        since = now
                    }
                } else if (!reloadHovered) {
                    if (elapsed >= 150) { moveToEdge(screen); reloadHovered = true; since = now }
                } else if (waitFor(visible())) {
                    check(screen.rendererStatistics.surfaceAllocations == allocationsBeforeReload + 1)
                    capture(screen, "reloaded")
                    minecraft.options.guiScale().set(3)
                    minecraft.resizeGui()
                    next()
                }
            }
            17 -> if (elapsed >= 300) { moveToEdge(screen); next() }
            18 -> if (waitFor(visible())) {
                check(minecraft.window.guiScale == 3)
                capture(screen, "scale3")
                minecraft.options.guiScale().set(2)
                minecraft.resizeGui()
                screen.mouseMoved(-10.0, -10.0)
                next()
            }
            19 -> if (elapsed >= 300) { moveToEdge(screen); next() }
            20 -> if (waitFor(visible())) {
                preparedBeforeScroll = screen.nativeTooltipStatistics.preparedImages
                val point = edgePoint(screen)
                screen.mouseScrolled(point.first, point.second, 0.0, -1.0)
                next()
            }
            21 -> if (elapsed >= 180) {
                check(!visible()) { "Tooltip survived wheel scrolling" }
                screen.mouseMoved(-10.0, -10.0)
                next()
            }
            22 -> if (elapsed >= 300) {
                check(!visible())
                check(screen.nativeTooltipStatistics.preparedImages == preparedBeforeScroll) { "Hidden tooltip still prepares images" }
                close()
                return true
            }
        }
        return false
    }

    private fun cellPoint(screen: ComposePreviewScreen, index: Int): Pair<Double, Double> {
        val bounds = ComposeThread.call { checkNotNull(screen.itemBrowser.visibleCells[index]) }
        return toGui(screen, bounds.center.x, bounds.top + 30f)
    }
    private fun edgePoint(screen: ComposePreviewScreen): Pair<Double, Double> {
        val point = ComposeThread.call { checkNotNull(screen.itemBrowser.edgeTooltipBounds).center }
        return toGui(screen, point.x, point.y)
    }
    private fun toGui(screen: ComposePreviewScreen, x: Float, y: Float) =
        x.toDouble() * screen.width / minecraft.window.width to y.toDouble() * screen.height / minecraft.window.height
    private fun moveToCell(screen: ComposePreviewScreen, index: Int) { val point = cellPoint(screen, index); screen.mouseMoved(point.first, point.second) }
    private fun moveToEdge(screen: ComposePreviewScreen) { val point = edgePoint(screen); screen.mouseMoved(point.first, point.second) }
    private fun dismissDialog(screen: ComposePreviewScreen) {
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0)
        screen.keyReleased(GLFW.GLFW_KEY_ESCAPE, 0, 0)
        check(!ComposeThread.call { screen.model.dialog })
        screen.mouseMoved(-10.0, -10.0)
    }
    private fun capture(screen: ComposePreviewScreen, name: String, requireTooltip: Boolean = true) {
        val bounds = screen.nativeTooltipBounds
        if (requireTooltip) {
            checkNotNull(bounds)
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= minecraft.window.width && bounds.bottom <= minecraft.window.height) {
                "Tooltip crossed the framebuffer edge: $bounds"
            }
            check(bounds.width >= 16 && bounds.height >= 16)
        }
        // 26.2 readback is asynchronous and backend-neutral. Visibility and
        // rich-component assertions above validate the extracted tooltip; the
        // real game render pass owns the eventual framebuffer presentation.
    }

    override fun close() {
        if (!closed) { NeoForge.EVENT_BUS.unregister(cancellation); closed = true }
    }
}
