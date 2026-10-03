package dev.compixel.development

import dev.compixel.bridge.ComposeThread
import dev.compixel.development.render.NativeDrawingVisualScreen
import dev.compixel.development.render.NativeItemPartialScreen
import dev.compixel.development.render.NativeItemVisualScreen
import dev.compixel.development.render.PortValidationScreen
import dev.compixel.development.render.verifyRenderer
import dev.compixel.forge.config.ComposeConfigScreen
import dev.compixel.testing.suite.AcceptanceLog
import dev.compixel.testing.suite.AcceptanceStep
import dev.compixel.testing.suite.ClientSuite
import dev.compixel.testing.suite.SuitePixels
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.locale.Language

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/**
 * Correctness suite: every [AcceptanceStep], in order, in a fresh flat creative world. Input goes through real Screen
 * callbacks and focus is logical, so it never needs, or steals, OS focus.
 */
internal class ClientAcceptanceProbe {
    private val minecraft
        get() = Minecraft.getInstance()

    private val log = AcceptanceLog()
    private val session = SuiteSession(ClientSuite.ACCEPTANCE, frameLimit = 260)
    private val script = SuiteScript(session)
    private val hud = HudAcceptance(session, log, script)
    private val preview = PreviewAcceptance(session, log, script)
    private var started = false
    private var finished = false
    private var world = false
    private var scripted = false
    private var inventory: InventoryAcceptanceProbe? = null
    private var menuSync: MenuSyncAcceptanceProbe? = null
    private var reload: CompletableFuture<Void>? = null

    /** After each screen render. Minecraft's marker is drawn after Compose, before any capture of this frame. */
    fun afterRender(screen: Screen, drawMarker: (Int) -> Unit) = guard {
        if (!started) {
            if (!SuitePlatform.overlayActive) start(screen)
            return@guard
        }
        session.checkWindow()
        if (session.rendered(screen) && preview.marker) drawMarker(SuitePixels.MARKER_ARGB)
    }

    /** After each client tick, outside rendering: every step's input and screen change happens here. */
    fun tick() = guard {
        if (!started) return@guard
        session.checkWindow()
        if (!world) {
            if (session.worldReady) worldLoaded()
            return@guard
        }
        inventory?.tick()
        menuSync?.tick()
        if (scripted) {
            script.advance()
            if (script.done) finish()
        }
    }

    private fun start(screen: Screen) {
        started = true
        val language = Language.getInstance()
        val previewCategory = SuitePlatform.keyCategory("key.compixel.open_preview")
        check(
            language.has("compixel.config.title") &&
                language.has("key.compixel.open_preview") &&
                previewCategory != null &&
                language.has(previewCategory)
        ) {
            "Library/development translations did not load"
        }
        check(SuitePlatform.loaderProduction == SuiteEnvironment.production) {
            "Loader production=${SuitePlatform.loaderProduction}, but the launch expects production=${SuiteEnvironment.production}"
        }
        log.pass(AcceptanceStep.RESOURCES, if (SuiteEnvironment.production) "production" else "development")
        session.prepare()
        log.pass(AcceptanceStep.RENDERER, verifyRenderer())
        session.createWorld(screen)
    }

    private fun worldLoaded() {
        world = true
        check(checkNotNull(minecraft.player).isCreative) { "The test world is not creative" }
        log.pass(AcceptanceStep.WORLD)
        inventory = InventoryAcceptanceProbe {
            inventory = null
            log.pass(AcceptanceStep.INVENTORY)
            menuSync = MenuSyncAcceptanceProbe {
                menuSync = null
                log.pass(AcceptanceStep.MENU_SYNC)
                config()
                port()
                nativeVisual()
                nativePartial()
                nativeDrawing()
                hud.schedule()
                preview.schedule()
                scripted = true
            }
        }
    }

    private fun config() {
        var file = ""
        lateinit var parent: PortValidationScreen
        lateinit var config: ComposeConfigScreen
        script.act("edit the acceptance config") { file = ConfigAcceptance.verify() }
        script.act("open the config screen") {
            parent = PortValidationScreen()
            config = ConfigAcceptance.screen(parent)
            session.open(config)
        }
        script.until("the config screen rendered its state") {
            config.rendererStatistics.renderedFrames > 0 && config.contentState.isOpen
        }
        script.act("close the config screen") {
            config.onClose()
            check(SuitePlatform.screen === parent) { "The config screen did not return to its parent" }
            check(!config.contentState.isOpen) { "The config screen kept its state" }
            session.requireReleased(config)
            session.adopt(parent)
            session.closeScreen()
            log.pass(AcceptanceStep.CONFIG, file)
        }
    }

    private fun port() {
        lateinit var port: PortValidationScreen
        fun click(x: Double, y: Double) {
            port.mouseMoved(x, y)
            port.mouseClicked(x, y, SuitePlatform.MOUSE_LEFT)
            port.mouseReleased(x, y, SuitePlatform.MOUSE_LEFT)
        }
        fun key(key: Int, modifiers: Int = 0) {
            port.keyPressed(key, 0, modifiers)
            port.keyReleased(key, 0, modifiers)
        }
        fun capture(name: String, scale: Int, clicked: Boolean) {
            script.pause(150)
            script.act("capture $name") { session.capture(name) { port.verifyPixels(it, scale, clicked) } }
            script.until("the $name capture") { session.capturesIdle }
        }
        script.act("open the port fixture") {
            port = PortValidationScreen()
            session.open(port)
        }
        script.until("the port fixture rendered") { port.rendererStatistics.renderedFrames > 0 }
        capture("port-orientation-alpha", 2, clicked = false)
        script.act("click the top-left target") { click(32.0, 32.0) }
        script.until("the top-left target received its click") { ComposeThread.call { port.model.clicks } == 1 }
        script.act("focus the port text field") { click(port.width / 2.0, 28.0) }
        script.pause(100)
        script.act("type Unicode text") { PORT_TEXT.forEach { port.charTyped(it, 0) } }
        script.until("Unicode text entry") { ComposeThread.call { port.model.text } == PORT_TEXT }
        script.act("select all and delete") {
            key(SuitePlatform.KEY_A, SuitePlatform.MOD_CONTROL)
            key(SuitePlatform.KEY_BACKSPACE)
        }
        script.until("the select-all/backspace shortcut") { ComposeThread.call { port.model.text }.isEmpty() }
        script.act("record ${AcceptanceStep.PORT_INPUT}") { log.pass(AcceptanceStep.PORT_INPUT) }
        script.act("switch to GUI scale 3") { SuitePlatform.setGuiScale(3) }
        script.until("GUI scale 3 applied") {
            SuitePlatform.guiScale == 3 && port.width == minecraft.window.guiScaledWidth
        }
        capture("port-gui-scale-3", 3, clicked = true)
        script.act("resize to 1000x700 at GUI scale 2") {
            SuitePlatform.setGuiScale(2)
            SuitePlatform.setWindowSize(1000, 700)
        }
        script.until("the framebuffer resized") {
            minecraft.window.width == 1000 &&
                minecraft.window.height == 700 &&
                SuitePlatform.guiScale == 2 &&
                port.width == minecraft.window.guiScaledWidth
        }
        capture("port-resize", 2, clicked = true)
        script.act("record ${AcceptanceStep.PORT_SCALE}") { log.pass(AcceptanceStep.PORT_SCALE) }
        script.act("reload resources") { reload = minecraft.reloadResourcePacks() }
        script.until("the resource reload", 120_000) { reload?.isDone == true }
        script.act("finish the reload") { checkNotNull(reload).join() }
        capture("port-resource-reload", 2, clicked = true)
        script.act("record ${AcceptanceStep.PORT_RELOAD}") { log.pass(AcceptanceStep.PORT_RELOAD) }
        script.act("restore 1280x960") { SuitePlatform.setWindowSize(1280, 960) }
        script.until("the framebuffer restored") { minecraft.window.width == 1280 && minecraft.window.height == 960 }
    }

    private fun nativeVisual() {
        lateinit var visual: NativeItemVisualScreen
        script.act("open the native visual fixture") {
            visual = NativeItemVisualScreen()
            session.open(visual)
        }
        script.until("native visual images prepared", 20_000) {
            visual.rendererStatistics.renderedFrames > 0 &&
                visual.nativeItemStatistics.let { it.cachedImages > 0 && it.pendingImages == 0 }
        }
        script.pause(200)
        script.act("capture the native visual fixture") {
            val bounds = visual.bounds()
            session.capture("native-visual") { visual.verifyPixels(it, bounds) }
        }
        script.until("the native visual capture") { session.capturesIdle }
        script.act("record ${AcceptanceStep.NATIVE_VISUAL}") { log.pass(AcceptanceStep.NATIVE_VISUAL) }
    }

    private fun nativePartial() {
        lateinit var partial: NativeItemPartialScreen
        var drawn = 0L
        var refreshed = 0L
        script.act("open the native partial redraw fixture") {
            partial = NativeItemPartialScreen()
            session.open(partial)
        }
        script.until("native partial images prepared", 20_000) {
            partial.rendererStatistics.renderedFrames > 0 &&
                partial.nativeItemStatistics.let { it.cachedImages == 4 && it.pendingImages == 0 }
        }
        script.act("remember native partial drawing") {
            drawn = partial.nativeItemStatistics.drawnIcons
            refreshed = partial.nativeItemStatistics.animationRefreshes
        }
        script.until("the ticking icon redrew", 20_000) {
            partial.nativeItemStatistics.animationRefreshes >= refreshed + 6
        }
        script.act("capture the native partial redraw fixture") {
            val items = partial.nativeItemStatistics
            check(items.pages == 1 && items.drawnIcons - drawn == items.animationRefreshes - refreshed) {
                "The atlas page redrew icons that were not due: $items"
            }
            val bounds = partial.bounds()
            session.capture("native-partial") { partial.verifyPixels(it, bounds) }
        }
        script.until("the native partial capture") { session.capturesIdle }
        script.act("record ${AcceptanceStep.NATIVE_PARTIAL}") {
            log.pass(AcceptanceStep.NATIVE_PARTIAL, partial.nativeItemStatistics.toString())
        }
    }

    private fun nativeDrawing() {
        lateinit var drawing: NativeDrawingVisualScreen
        var still = 0
        var ticking = 0
        fun ready() =
            drawing.nativeDrawingStatistics.let { it.cachedImages == 5 && it.pendingImages == 0 && it.pages == 5 }
        fun capture(name: String) {
            script.until("rectangular drawings ready for $name", 20_000) { ready() }
            script.pause(100)
            script.act("capture $name") {
                drawing.verifyDimensions()
                val bounds = drawing.bounds()
                session.capture(name) { drawing.verifyPixels(it, bounds) }
            }
            script.until("the $name capture") { session.capturesIdle }
        }
        script.act("open rectangular native drawing") {
            drawing = NativeDrawingVisualScreen()
            session.open(drawing)
        }
        capture("native-drawing")
        script.act("remember rectangular refreshes") {
            still = drawing.staticDraws
            ticking = drawing.tickingDraws
        }
        script.until("native drawing tick refreshes") { drawing.tickingDraws >= ticking + 5 }
        script.act("static native drawings stayed cached") { check(drawing.staticDraws == still) }
        script.act("resize native drawing layout") { ComposeThread.call { drawing.model.expanded = true } }
        capture("native-drawing-layout")
        script.act("resize rectangular drawing viewport") {
            SuitePlatform.setGuiScale(3)
            SuitePlatform.setWindowSize(1001, 751)
        }
        script.until("rectangular viewport resized") {
            minecraft.window.width == 1001 && minecraft.window.height == 751 && SuitePlatform.guiScale == 3
        }
        capture("native-drawing-scale")
        script.act("reload rectangular drawing resources") { reload = minecraft.reloadResourcePacks() }
        script.until("rectangular drawing resource reload", 120_000) { reload?.isDone == true }
        script.act("finish rectangular resource reload") { checkNotNull(reload).join() }
        capture("native-drawing-reload")
        script.act("detach native drawings") { ComposeThread.call { drawing.model.visible = false } }
        script.until("detached native drawings released") {
            drawing.nativeDrawingStatistics.let { it.pages == 0 && it.preparedImages == it.retiredImages }
        }
        script.act("close native drawing fixture") {
            session.closeScreen()
            check(drawing.rendererStatistics.strandedNativeImages == 0)
            log.pass(AcceptanceStep.NATIVE_DRAWING)
            SuitePlatform.setGuiScale(2)
            SuitePlatform.setWindowSize(1280, 960)
        }
        script.until("native drawing viewport restored") {
            minecraft.window.width == 1280 && minecraft.window.height == 960 && SuitePlatform.guiScale == 2
        }
    }

    private fun finish() {
        finished = true
        check(session.capturesIdle) { "Captures are still pending" }
        session.rethrowCaptureFailure()
        preview.release()
        session.finish(log.report(session.header))
    }

    private inline fun guard(block: () -> Unit) {
        if (finished) return
        try {
            block()
            session.rethrowCaptureFailure()
        } catch (failure: Throwable) {
            finished = true
            preview.release()
            session.fail(log.failure(session.header, failure), failure)
        }
    }

    private companion object {
        const val PORT_TEXT = "Port中😀"
    }
}
