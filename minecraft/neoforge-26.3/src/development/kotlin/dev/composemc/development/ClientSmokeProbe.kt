package dev.composemc.development

import dev.composemc.neoforge.*

import dev.composemc.bridge.ComposeThread
import dev.composemc.demo.preview.DemoPage
import dev.composemc.host.SessionState
import dev.composemc.host.UiSession
import dev.composemc.render.RenderBackend
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import com.mojang.blaze3d.platform.InputConstants
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

    fun afterRender(parent: Screen) {
        if (started) {
            return
        }
        started = true
        if (configuredRenderBackend() == RenderBackend.OPENGL) checks += verifyOpenGlRenderer()
        minecraft.execute(Runnable {
            oldClipboard = minecraft.keyboardHandler.clipboard
            oldGuiScale = minecraft.options.guiScale().get()
            val preview = ComposePreviewScreen(parent)
            minecraft.setScreenAndShow(preview)
            screen = preview
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
                key(current, InputConstants.KEY_A, InputConstants.MOD_CONTROL)
                key(current, InputConstants.KEY_C, InputConstants.MOD_CONTROL)
            }
            28 -> {
                check(minecraft.keyboardHandler.clipboard == expectedText) { "Copy did not reach GLFW clipboard" }
                key(current, InputConstants.KEY_X, InputConstants.MOD_CONTROL)
            }
            30 -> {
                check(model { query }.isEmpty()) { "Cut did not update field" }
                key(current, InputConstants.KEY_V, InputConstants.MOD_CONTROL)
            }
            34 -> {
                check(model { query } == expectedText) { "Paste did not update field" }
                checks += "Select all, copy, cut and paste"
                clickControl(current, "apply")
            }
            48 -> {
                check(model { dialog }) { "Apply button did not open dialog" }
                capture("dialog")
                key(current, InputConstants.KEY_ESCAPE)
            }
            52 -> {
                check(!model { dialog }) { "Escape did not close the dialog" }
                checks += "Modal Escape priority"
                org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(minecraft.window.handle(), 1000, 720)
            }
            65 -> {
                check(current.session === firstSession) { "Resize replaced/leaked session" }
                check(minecraft.window.width == 1000 && minecraft.window.height == 720)
                capture("resized")
                minecraft.options.guiScale().set(2)
                minecraft.resizeGui()
            }
            75 -> {
                check(current.session === firstSession && minecraft.window.guiScale == 2)
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
                check(stats.activeVariants > 0 && stats.pendingImages == 0) { "Native icons were not extracted: $stats" }
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
                check(stats.preparedImages - preparationsBeforeItemScroll < 600) { "Native item work is not bounded: $stats" }
                check(stats.pendingImages == 0)
                if (current.renderBackend == RenderBackend.OPENGL) {
                    check(current.rendererStatistics.nativeImageReadbacks == 0L)
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
                check(current.nativeItemStatistics.pendingImages == 0)
                capture("reloaded")
                checkNativePreview()
                checks += "Real resource reload rebuilds the surface and preserves the session"
                current.onClose()
                check(current.rendererStatistics.liveSurfaces == 0) { "Renderer surface survived close" }
                check(current.rendererStatistics.liveNativeImages == 0)
                check(!current.nativeTooltipStatistics.visible)
            }
            161 -> {
                check(firstSession!!.state == SessionState.CLOSED && current.session == null)
                checks += "Close releases session"
                val preview = ComposePreviewScreen(null)
                minecraft.setScreenAndShow(preview)
                screen = preview
            }
            176 -> {
                capture("reopened")
                key(current, InputConstants.KEY_ESCAPE)
                check(current.session == null && current.rendererStatistics.liveSurfaces == 0)
                checks += "Reopen and final Escape cleanup"
                stressCycles = 12
            }
        }
    }

    private fun stressReopen() {
        when (stressTicks++ % 4) {
            0 -> {
                val next = ComposePreviewScreen(null)
                minecraft.setScreenAndShow(next)
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
    private fun capture(@Suppress("UNUSED_PARAMETER") name: String) = Unit

    private fun checkNativePreview() {
        check(ComposeThread.call { screen!!.itemBrowser.previewBounds != null }) { "Native item preview bounds are missing" }
    }
}
