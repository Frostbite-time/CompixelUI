package dev.composemc.development

import dev.composemc.bridge.ComposeThread
import dev.composemc.development.render.PortValidationScreen
import dev.composemc.forge.closeHudLayers
import dev.composemc.host.SessionState
import dev.composemc.host.UiSession
import dev.composemc.testing.suite.AcceptanceLog
import dev.composemc.testing.suite.AcceptanceStep
import dev.composemc.testing.suite.PixelRect
import dev.composemc.testing.suite.SuitePixels
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/** The development HUD layer over the test world, with no screen open unless a step opens one above it. */
internal class HudAcceptance(
    private val session: SuiteSession,
    private val log: AcceptanceLog,
    private val script: SuiteScript,
) {
    private val minecraft
        get() = Minecraft.getInstance()

    private val hud
        get() = SuiteHud.layer

    private lateinit var first: UiSession
    private lateinit var port: PortValidationScreen
    private var frames = 0L
    private var reload: CompletableFuture<Void>? = null

    fun schedule() {
        draw()
        visibility()
        resize()
        release()
    }

    private fun draw() {
        script.act("return to the game view and show the HUD") {
            session.closeScreen()
            SuiteHud.show(SuiteHudMode.ACCEPTANCE)
        }
        script.until("the HUD drew its panel and native item", 20_000) {
            hud.rendererStatistics.renderedFrames > 0 &&
                layout() != null &&
                hud.nativeItemStatistics.let { it.cachedImages > 0 && it.pendingImages == 0 }
        }
        script.act("remember the HUD session") { first = checkNotNull(hud.session) { "The HUD has no session" } }
        script.pause(150)
        capture("hud")
        script.act("open a screen above the HUD") {
            port = PortValidationScreen()
            session.open(port)
            frames = hud.rendererStatistics.renderedFrames
        }
        script.until("the HUD kept drawing beneath the screen") {
            SuiteHud.advance()
            hud.rendererStatistics.renderedFrames > frames + 2
        }
        // The port target lies over the HUD panel: the screen above takes the click.
        script.act("click the screen over the HUD panel") {
            port.mouseMoved(32.0, 32.0)
            port.mouseClicked(32.0, 32.0, SuitePlatform.MOUSE_LEFT)
            port.mouseReleased(32.0, 32.0, SuitePlatform.MOUSE_LEFT)
        }
        script.until("the screen above received the click") { ComposeThread.call { port.model.clicks } == 1 }
        script.act("check that the HUD received no input") {
            check(ComposeThread.call { SuiteHud.model.pointerEvents } == 0) { "Input reached the HUD layer" }
            requireSession("A screen above the HUD")
        }
        pass(AcceptanceStep.HUD_RENDER) { hud.nativeItemStatistics.toString() }
    }

    private fun visibility() {
        script.act("close the screen above the HUD") {
            session.closeScreen()
            frames = hud.rendererStatistics.renderedFrames
            SuiteHud.advance()
        }
        script.until("the HUD drew over the game view") { hud.rendererStatistics.renderedFrames > frames }
        script.act("hide the GUI") {
            SuitePlatform.hudHidden = true
            SuiteHud.advance()
        }
        script.pause(300)
        script.act("capture the hidden HUD") {
            frames = hud.rendererStatistics.renderedFrames
            val (panel, _) = checkNotNull(layout()) { "The HUD panel is not laid out" }
            session.capture("hud-hidden") { SuitePixels.requireHudHidden(it, "hud-hidden", panel) }
        }
        script.until("the hidden capture") { session.capturesIdle }
        script.pause(300)
        script.act("check that the hidden HUD stopped drawing") {
            check(hud.rendererStatistics.renderedFrames == frames) { "The HUD drew while the GUI was hidden" }
            SuitePlatform.hudHidden = false
        }
        script.until("the HUD drew again") { hud.rendererStatistics.renderedFrames > frames }
        script.act("check the shown HUD session") { requireSession("Hiding the GUI") }
        script.pause(150)
        capture("hud-shown")
        pass(AcceptanceStep.HUD_VISIBILITY)
    }

    private fun resize() {
        script.act("switch to GUI scale 3") { SuitePlatform.setGuiScale(3) }
        script.until("the HUD laid out at GUI scale 3") { SuitePlatform.guiScale == 3 && laidOutAt(3) }
        script.pause(150)
        capture("hud-gui-scale-3")
        script.act("resize to 1000x700 at GUI scale 2") {
            SuitePlatform.setGuiScale(2)
            SuitePlatform.setWindowSize(1000, 700)
        }
        script.until("the HUD followed the framebuffer") {
            minecraft.window.width == 1000 &&
                minecraft.window.height == 700 &&
                SuitePlatform.guiScale == 2 &&
                laidOutAt(2)
        }
        script.pause(150)
        capture("hud-resize")
        script.act("reload resources") { reload = minecraft.reloadResourcePacks() }
        script.until("the resource reload", 120_000) { reload?.isDone == true }
        script.act("finish the reload") { checkNotNull(reload).join() }
        script.until("the HUD item prepared after the reload", 20_000) {
            hud.nativeItemStatistics.let { it.cachedImages > 0 && it.pendingImages == 0 }
        }
        script.pause(150)
        capture("hud-reloaded")
        script.act("check the resized HUD session") { requireSession("Resize and reload") }
        script.act("restore 1280x960") { SuitePlatform.setWindowSize(1280, 960) }
        script.until("the framebuffer restored") {
            minecraft.window.width == 1280 && minecraft.window.height == 960 && laidOutAt(2)
        }
        pass(AcceptanceStep.HUD_RESIZE)
    }

    private fun release() {
        script.act("leave the world") {
            closeHudLayers() // The client's own handler when the player leaves the world.
            check(first.state == SessionState.CLOSED && hud.session == null) { "The HUD kept its session" }
            requireReleased("Leaving the world")
        }
        script.until("the next drawn frame opened a new session") {
            val next = hud.session
            next != null && next !== first && hud.rendererStatistics.renderedFrames > 0
        }
        script.act("close the HUD") {
            SuiteHud.hide()
            check(hud.session == null) { "close() kept the HUD session" }
            requireReleased("close()")
        }
        pass(AcceptanceStep.HUD_RELEASE)
    }

    private fun pass(step: AcceptanceStep, detail: () -> String? = { null }) =
        script.act("record $step") { log.pass(step, detail()) }

    private fun requireSession(change: String) = check(hud.session === first) { "$change replaced the HUD session" }

    private fun requireReleased(change: String) {
        val renderer = hud.rendererStatistics
        val items = hud.nativeItemStatistics
        check(renderer.liveSurfaces == 0 && renderer.liveNativeImages == 0) {
            "$change leaked HUD renderer resources: $renderer"
        }
        check(items.preparedImages == items.retiredImages) { "$change leaked HUD item images: $items" }
    }

    /** The panel and its item in framebuffer pixels, once laid out. */
    private fun layout(): Pair<PixelRect, PixelRect>? = ComposeThread.call {
        val panel = SuiteHud.model.panel ?: return@call null
        val item = SuiteHud.model.item ?: return@call null
        pixels(panel) to pixels(item)
    }

    private fun pixels(rect: androidx.compose.ui.geometry.Rect) =
        PixelRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())

    /** The panel keeps its place and size in dp: GUI scale [scale] is the HUD's density. */
    private fun laidOutAt(scale: Int): Boolean {
        val (panel, _) = layout() ?: return false
        return panel.left == SuiteHud.INSET_DP * scale &&
            panel.top == SuiteHud.INSET_DP * scale &&
            panel.right - panel.left == SuiteHud.PANEL_WIDTH_DP * scale &&
            panel.bottom - panel.top == SuiteHud.PANEL_HEIGHT_DP * scale
    }

    private fun capture(name: String) {
        script.act("capture $name") {
            val (panel, item) = checkNotNull(layout()) { "The HUD panel is not laid out" }
            session.capture(name) { SuitePixels.requireHud(it, name, panel, item) }
        }
        script.until("the $name capture") { session.capturesIdle }
    }
}
