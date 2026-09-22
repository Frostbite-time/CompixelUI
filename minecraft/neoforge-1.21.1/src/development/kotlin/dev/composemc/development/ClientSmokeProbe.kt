package dev.composemc.development

import dev.composemc.neoforge.*

import dev.composemc.bridge.ComposeThread
import dev.composemc.demo.preview.DemoPage
import dev.composemc.host.SessionState
import dev.composemc.host.UiSession
import dev.composemc.render.RenderBackend
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.concurrent.CompletableFuture

/** Opt-in, deterministic integration probe. Calls real Screen callbacks, not OS input injection. */
internal class ClientSmokeProbe {
    private val minecraft get() = Minecraft.getInstance()
    private var started = false
    private var done = false
    private var ticks = 0
    private var screen: ComposePreviewScreen? = null
    private var firstSession: UiSession? = null
    private var oldClipboard: String? = null
    private var oldGuiScale = 0
    private val expectedText = "NeoForge 1.21.1 \uD83D\uDE00"
    private val checks = mutableListOf<String>()
    private var reloading: CompletableFuture<Void>? = null
    private var reloadEpoch = 0L
    private var allocationsBeforeReload = 0L
    private var idleGeneration = 0L
    private var idleSince = 0L
    private var idleDeadline = 0L
    private var stressTicks = 0
    private var stressCycles = 0
    private var previewPixels: IntArray? = null
    private var tooltipProbe: NativeTooltipProbe? = null
    private var preparationsBeforeItemScroll = 0L

    fun afterRender(parent: Screen, graphics: GuiGraphics) {
        if (minecraft.overlay != null) return
        if (started) {
            if (parent is ComposePreviewScreen && ticks in 103..156) {
                // A Minecraft draw after Compose catches projection, viewport and cached-state pollution.
                graphics.fill(0, 0, 4, 4, 0xFF22CC66.toInt())
                graphics.flush()
            }
            return
        }
        started = true
        if (configuredRenderBackend() == RenderBackend.OPENGL) checks += verifyOpenGlRenderer()
        minecraft.tell(Runnable {
            oldClipboard = minecraft.keyboardHandler.clipboard
            oldGuiScale = minecraft.options.guiScale().get()
            NeoForge.EVENT_BUS.post(ScreenEvent.KeyPressed.Pre(parent, GLFW.GLFW_KEY_F8, 0, 0))
            screen = minecraft.screen as? ComposePreviewScreen ?: error("F8 did not open preview")
            firstSession = screen!!.session
            checks += "F8 registration and open"
        })
    }

    fun tick() {
        try { advance() } catch (failure: Throwable) {
            tooltipProbe?.close()
            oldClipboard?.let { minecraft.keyboardHandler.clipboard = it }
            minecraft.options.guiScale().set(oldGuiScale)
            throw failure
        }
    }

    private fun advance() {
        if (done) return
        // This probe injects Screen callbacks, not OS events. Do not inject while unfocused.
        if (!minecraft.isWindowActive) return
        reloading?.let {
            if (!it.isDone) return
            it.join()
            reloading = null
        }
        if (minecraft.overlay != null) return
        if (stressCycles > 0) { stressReopen(); return }
        val current = screen ?: return
        tooltipProbe?.let { probe ->
            if (probe.advance(current)) {
                checks += probe.report
                tooltipProbe = null
                preparationsBeforeItemScroll = current.nativeItemStatistics.preparedImages
                current.session!!.post(Runnable { current.itemBrowser.scrollTarget = 64 })
            }
            return
        }
        ticks++
        when (ticks) {
            15 -> {
                capture("settings")
                idleGeneration = current.rendererStatistics.lastFrameGeneration
                idleSince = System.nanoTime()
                idleDeadline = idleSince + 5_000_000_000L
            }
            19 -> {
                val now = System.nanoTime()
                val generation = current.rendererStatistics.lastFrameGeneration
                if (generation != idleGeneration) {
                    check(now < idleDeadline) { "UI did not settle after startup animations: " + current.rendererStatistics }
                    idleGeneration = generation
                    idleSince = now
                }
                // Client ticks can catch up in a batch while the first frames compile shaders.
                // Require an actual quiet wall-clock interval rather than a fixed tick number.
                if (now - idleSince < 200_000_000L) { ticks = 18; return }
                checks += "Static UI reuses its retained frame"
            }
            20 -> clickControl(current, "profile")
            22 -> expectedText.forEach { current.charTyped(it, 0) }
            26 -> {
                check(model { query } == expectedText) { "Character input mismatch: ${model { query }}" }
                checks += "Text and supplementary Unicode input"
                key(current, GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL)
                key(current, GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL)
            }
            28 -> {
                check(minecraft.keyboardHandler.clipboard == expectedText) { "Copy did not reach GLFW clipboard" }
                key(current, GLFW.GLFW_KEY_X, GLFW.GLFW_MOD_CONTROL)
            }
            30 -> {
                check(model { query }.isEmpty()) { "Cut did not update field" }
                key(current, GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_CONTROL)
            }
            34 -> {
                check(model { query } == expectedText) { "Paste did not update field" }
                checks += "Select all, copy, cut and paste"
                clickControl(current, "apply")
            }
            48 -> {
                check(model { dialog }) { "Apply button did not open dialog" }
                capture("dialog")
                key(current, GLFW.GLFW_KEY_ESCAPE)
            }
            52 -> {
                check(minecraft.screen === current && !model { dialog }) { "Escape closed Screen before dialog" }
                checks += "Modal Escape priority"
                GLFW.glfwSetWindowSize(minecraft.window.window, 1000, 720)
            }
            65 -> {
                check(current.session === firstSession) { "Resize replaced/leaked session" }
                check(minecraft.window.width == 1000 && minecraft.window.height == 720)
                capture("resized")
                minecraft.options.guiScale().set(2)
                minecraft.resizeDisplay()
            }
            75 -> {
                check(current.session === firstSession && minecraft.window.guiScale == 2.0)
                checks += "Window resize and GUI scale preserve session"
                current.session!!.post(Runnable { current.model.query = ""; current.model.page = DemoPage.Catalog; current.model.count = 100000 })
            }
            85 -> {
                capture("catalog")
                clickControl(current, "entry:1")
            }
            87 -> {
                check(model { selected } == 1) { "List hit testing failed after GUI scale change" }
                val bounds = ComposeThread.call { current.model.bounds.getValue("list") }
                current.mouseScrolled(bounds.center.x.toDouble() * current.width / minecraft.window.width,
                    bounds.center.y.toDouble() * current.height / minecraft.window.height, 0.0, -8.0)
            }
            100 -> {
                click(current, 100.0, 415.0)
            }
            102 -> {
                check(model { selected } > 1) { "Wheel scroll did not advance list" }
                checks += "List hit testing and wheel scrolling after resize"
                capture("scrolled")
                val stats = current.rendererStatistics
                checks += stats.toString()
                checks += "CPU recording: " + current.recordingTimings.summary()
                checks += "CPU rendering: " + current.renderingTimings.summary()
                checks += "CPU presentation: " + current.presentationTimings.summary()
                val allocations = stats.surfaceAllocations
                check(allocations <= 3) { "Texture recreated every frame: $allocations" }
                check(stats.renderedFrames > 0 && stats.liveSurfaces == 1)
                if (current.renderBackend == RenderBackend.OPENGL) check(stats.fullFrameUploads == 0L)
                checks += "100k list render and texture reuse ($allocations allocations)"
                current.session!!.post(Runnable { current.model.page = DemoPage.Items; current.itemBrowser.count = 10000 })
            }
            116 -> {
                capture("items")
                checkNativePreview()
                val stats = current.nativeItemStatistics
                check(stats.activeVariants > 0 && stats.cachedImages >= stats.activeVariants && stats.pendingImages == 0) { "Native icons were not prepared: $stats" }
                val point = ComposeThread.call { Triple(current.itemBrowser.hitX, current.itemBrowser.hitY, current.itemBrowser.firstVisible) }
                val guiX = point.first * current.width / minecraft.window.width
                val guiY = point.second * current.height / minecraft.window.height
                current.mouseClicked(guiX.toDouble(), guiY.toDouble(), 0)
                current.mouseReleased(guiX.toDouble(), guiY.toDouble(), 0)
                check(ComposeThread.call { current.itemBrowser.selected } == point.third) { "Native item grid hit testing failed" }
                tooltipProbe = NativeTooltipProbe()
            }
            126 -> current.session!!.post(Runnable { current.itemBrowser.scrollTarget = 128 })
            136 -> current.session!!.post(Runnable { current.itemBrowser.scrollTarget = 192 })
            146 -> {
                capture("items-scrolled")
                checkNativePreview()
                val stats = current.nativeItemStatistics
                check(stats.cachedImages <= 128 && stats.preparedImages - preparationsBeforeItemScroll < 600) { "Native item work is not bounded: $stats" }
                check(stats.retiredImages > 0) { "Native item scrolling never exercised retirement" }
                check(stats.pendingImages == 0)
                if (current.renderBackend == RenderBackend.OPENGL) {
                    check(current.rendererStatistics.nativeImageReadbacks == 0L)
                    check(current.rendererStatistics.nativeImageCopies > 0L)
                }
                checks += "Native item icons, scale/clip/rotate, grid hit testing and 10k-row scrolling: $stats"
                checks += "Native image backend: " + current.rendererStatistics
                reloadEpoch = RendererResources.epoch
                allocationsBeforeReload = current.rendererStatistics.surfaceAllocations
                reloading = minecraft.reloadResourcePacks()
            }
            156 -> {
                check(RendererResources.epoch > reloadEpoch) { "Resource reload listener did not run" }
                check(current.session === firstSession) { "Resource reload replaced the Compose session" }
                check(current.rendererStatistics.surfaceAllocations == allocationsBeforeReload + 1) { "Resource reload did not rebuild one retained surface" }
                check(current.rendererStatistics.liveSurfaces == 1)
                check(current.nativeItemStatistics.cachedImages > 0 && current.nativeItemStatistics.pendingImages == 0)
                capture("reloaded")
                checkNativePreview()
                checks += "Real resource reload rebuilds the surface and preserves the session"
                current.onClose()
                check(current.rendererStatistics.liveSurfaces == 0) { "Renderer surface survived close" }
                check(current.rendererStatistics.liveNativeImages == 0 && current.nativeItemStatistics.cachedImages == 0)
                check(!current.nativeTooltipStatistics.visible)
            }
            161 -> {
                check(firstSession!!.state == SessionState.CLOSED && current.session == null)
                checks += "Close releases session"
                val parent = checkNotNull(minecraft.screen)
                NeoForge.EVENT_BUS.post(ScreenEvent.KeyPressed.Pre(parent, GLFW.GLFW_KEY_F8, 0, 0))
                check(minecraft.screen is ComposePreviewScreen)
                screen = minecraft.screen as ComposePreviewScreen
            }
            176 -> {
                capture("reopened")
                key(current, GLFW.GLFW_KEY_ESCAPE)
                check(current.session == null && current.rendererStatistics.liveSurfaces == 0)
                checks += "Reopen and final Escape cleanup"
                stressCycles = 12
            }
        }
    }

    private fun stressReopen() {
        when (stressTicks++ % 4) {
            0 -> {
                val next = ComposePreviewScreen(minecraft.screen)
                minecraft.setScreen(next)
                screen = next
            }
            3 -> {
                val current = checkNotNull(screen)
                val session = checkNotNull(current.session)
                check(current.rendererStatistics.renderedFrames > 0)
                check(current.rendererStatistics.liveSurfaces == 1)
                current.onClose()
                check(session.state == SessionState.CLOSED)
                check(current.rendererStatistics.liveSurfaces == 0)
                stressCycles--
                if (stressCycles == 0) {
                    checks += "12 repeated Screen opens/renders/closes release their sessions and surfaces"
                    oldClipboard?.let { minecraft.keyboardHandler.clipboard = it }
                    minecraft.options.guiScale().set(oldGuiScale)
                    File(minecraft.gameDirectory, "composemc-smoke.txt").writeText("PASS\n" + checks.joinToString("\n") + "\n")
                    done = true
                    minecraft.stop()
                }
            }
        }
    }

    private fun <T> model(block: dev.composemc.demo.preview.DemoModel.() -> T): T = ComposeThread.call { screen!!.model.block() }
    private fun click(screen: Screen, guiX: Double, guiY: Double) {
        screen.mouseMoved(guiX, guiY)
        screen.mouseClicked(guiX, guiY, 0)
        screen.mouseReleased(guiX, guiY, 0)
    }
    private fun clickControl(screen: ComposePreviewScreen, id: String) {
        val bounds = ComposeThread.call { screen.model.bounds.getValue(id) }
        val x = bounds.center.x.toDouble() * screen.width / minecraft.window.width
        val y = bounds.center.y.toDouble() * screen.height / minecraft.window.height
        screen.mouseMoved(x, y)
        screen.mouseClicked(x, y, 0)
        screen.mouseReleased(x, y, 0)
    }
    private fun key(screen: Screen, key: Int, modifiers: Int = 0) {
        screen.keyPressed(key, 0, modifiers)
        screen.keyReleased(key, 0, modifiers)
    }
    private fun capture(name: String) {
        Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { image ->
            val colors = HashSet<Int>()
            for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) colors += image.getPixelRGBA(x, y)
            image.writeToFile(File(minecraft.gameDirectory, "composemc-$name.png"))
            check(colors.size > 20) { "Blank game framebuffer: $name, colors=" + colors.size + ", " + screen?.rendererStatistics }
            if (name == "items" || name == "items-scrolled" || name == "reloaded") {
                val edge = 4 * image.width / checkNotNull(screen).width
                check(image.getPixelRGBA(2, 2) and 0xFFFFFF == 0x66CC22) { "Minecraft drawing failed after native item preparation" }
                check(image.getPixelRGBA(edge + 2, 2) and 0xFFFFFF != 0x66CC22) { "Minecraft projection/viewport was not restored" }
            }
        }
    }

    private fun checkNativePreview() {
        val bounds = ComposeThread.call { checkNotNull(screen!!.itemBrowser.previewBounds) }
        Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { image ->
            val width = bounds.width.toInt()
            val height = bounds.height.toInt()
            val pixels = IntArray(width * height) { index ->
                image.getPixelRGBA(bounds.left.toInt() + index % width, bounds.top.toInt() + index / width)
            }
            check(pixels.count { (it and 255) > (it ushr 8 and 255) && (it and 255) > 40 } > 50) {
                "Rotated/clipped item preview is missing or faded"
            }
            val reference = previewPixels
            if (reference == null) previewPixels = pixels
            else {
                check(reference.size == pixels.size)
                val changed = pixels.indices.count { index ->
                    (0..2).any { channel ->
                        kotlin.math.abs((pixels[index] ushr (channel * 8) and 255) -
                            (reference[index] ushr (channel * 8) and 255)) > 2
                    }
                }
                check(changed <= pixels.size / 100) { "Item alpha or clipping changed across repaint/reload: $changed pixels" }
            }
        }
    }
}
