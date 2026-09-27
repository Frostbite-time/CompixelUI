package dev.composemc.development

import dev.composemc.bridge.ComposeThread
import dev.composemc.demo.preview.DemoModel
import dev.composemc.demo.preview.DemoPage
import dev.composemc.host.SessionState
import dev.composemc.host.UiSession
import dev.composemc.render.RenderBackend
import dev.composemc.testing.suite.AcceptanceLog
import dev.composemc.testing.suite.AcceptanceStep
import dev.composemc.testing.suite.SuitePixels
import dev.composemc.testing.ui.OreComponentExercise
import dev.composemc.testing.ui.oreComponentPages
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/** The real F8 preview, driven through Screen callbacks in a world-backed client. */
internal class PreviewAcceptance(
    private val session: SuiteSession,
    private val log: AcceptanceLog,
    private val script: SuiteScript,
) {
    private val minecraft
        get() = Minecraft.getInstance()

    private val parent by lazy { SuiteParentScreen() }
    private lateinit var screen: ComposePreviewScreen
    private lateinit var first: UiSession
    /** Minecraft draws the marker after Compose while the native item page is under test. */
    var marker = false
        private set

    private var tooltips: NativeTooltipProbe? = null
    private val themes = ThemeAcceptance(session, script, log)
    private var reference: IntArray? = null
    private var reload: CompletableFuture<Void>? = null
    private var entry = 0.0 to 0.0
    private var epoch = 0L
    private var allocations = 0L
    private var prepared = 0L
    private var firstVisible = 0
    private var selectedBefore = 0
    private var idleGeneration = 0L
    private var idleSince = 0L

    fun schedule() {
        openPreview()
        retainedFrame()
        textField()
        modal()
        resize()
        list()
        nativeItems()
        nativeTooltips()
        nativeScroll()
        reloadResources()
        focus()
        closePreview()
        reopenPreview()
        components()
        themes.schedule()
        stress()
    }

    fun release() {
        themes.release()
        tooltips?.close()
        tooltips = null
    }

    private fun openPreview() {
        script.act("open the suite parent screen") { session.open(parent) }
        script.act("press F8 on the parent screen") {
            adoptPreview("F8 did not open the preview")
            first = checkNotNull(screen.session) { "The preview has no Compose session" }
        }
        script.until("the preview rendered") { screen.rendererStatistics.renderedFrames > 0 }
        pass(AcceptanceStep.PREVIEW_OPEN)
    }

    private fun retainedFrame() {
        script.act("watch for a quiet retained frame") {
            idleGeneration = screen.rendererStatistics.lastFrameGeneration
            idleSince = System.nanoTime()
        }
        script.until("static UI stopped repainting for 200 ms", 6_000) {
            val generation = screen.rendererStatistics.lastFrameGeneration
            val now = System.nanoTime()
            if (generation != idleGeneration) {
                idleGeneration = generation
                idleSince = now
            }
            now - idleSince >= 200_000_000L
        }
        pass(AcceptanceStep.RETAINED_FRAME)
    }

    private fun textField() {
        script.act("focus the profile field") { click(control("profile")) }
        script.pause(100)
        script.until("text input opened for the field") { SuitePlatform.textInputOpen(screen) }
        if (SuitePlatform.preeditSupported) {
            script.act("compose text with the input method") { SuitePlatform.preedit(screen, COMPOSING) }
            script.until("the composition shows in the field") { model { query } == COMPOSING }
            script.act("click the field while composing") { click(control("profile")) }
            script.until("the click discarded the composition") { model { query }.isEmpty() }
        }
        script.act("type supplementary Unicode text") { TEXT.forEach { screen.charTyped(it, 0) } }
        script.until("the typed text reached the field") { model { query } == TEXT }
        script.act("select all and delete") {
            key(SuitePlatform.KEY_A, SuitePlatform.MOD_CONTROL)
            key(SuitePlatform.KEY_BACKSPACE)
        }
        script.until("select-all delete emptied the field") { model { query }.isEmpty() }
        pass(AcceptanceStep.TEXT_FIELD)
    }

    private fun modal() {
        script.act("click Apply") { click(control("apply")) }
        script.until("Apply opened the dialog") { model { dialog } }
        script.act("press Escape") { key(SuitePlatform.KEY_ESCAPE) }
        script.until("Escape closed the dialog") { !model { dialog } }
        script.act("check that the screen stayed open") {
            check(SuitePlatform.screen === screen) { "Escape closed the screen before its dialog" }
        }
        pass(AcceptanceStep.MODAL)
    }

    private fun resize() {
        script.act("resize the window to 1000x720") { SuitePlatform.setWindowSize(1000, 720) }
        script.until("the framebuffer resized") {
            minecraft.window.width == 1000 &&
                minecraft.window.height == 720 &&
                screen.width == minecraft.window.guiScaledWidth
        }
        script.act("switch to GUI scale 3") {
            requireSession("Window resize")
            SuitePlatform.setGuiScale(3)
        }
        script.until("GUI scale 3 applied") {
            SuitePlatform.guiScale == 3 && screen.width == minecraft.window.guiScaledWidth
        }
        script.act("switch back to GUI scale 2") {
            requireSession("GUI scale 3")
            SuitePlatform.setGuiScale(2)
        }
        script.until("GUI scale 2 applied") {
            SuitePlatform.guiScale == 2 && screen.width == minecraft.window.guiScaledWidth
        }
        script.act("capture the resized preview") {
            requireSession("GUI scale 2")
            session.capture("preview-resized") { SuitePixels.requireContent(it, "preview-resized") }
        }
        script.until("the resized capture") { session.capturesIdle }
        pass(AcceptanceStep.SESSION_RESIZE)
    }

    private fun list() {
        script.act("open the 100k catalog") {
            post {
                screen.model.query = ""
                screen.model.page = DemoPage.Catalog
                screen.model.count = 100000
            }
        }
        script.until("the catalog laid out") {
            model { page == DemoPage.Catalog && count == 100000 && "entry:1" in bounds && "list" in bounds }
        }
        script.act("click entry 1") {
            entry = control("entry:1")
            click(entry)
        }
        script.until("list hit testing selected entry 1") { model { selected } == 1 }
        script.act("scroll the list") {
            val list = control("list")
            screen.mouseScrolled(list.first, list.second, 0.0, -8.0)
        }
        script.pause(400)
        script.act("click the same point") { click(entry) }
        script.until("wheel scrolling moved the list") { model { selected } > 1 }
        script.act("check texture reuse") {
            val renderer = screen.rendererStatistics
            allocations = renderer.surfaceAllocations
            check(allocations <= 3) { "Texture recreated every frame: $renderer" }
            check(renderer.renderedFrames > 0 && renderer.liveSurfaces == 1) { "Unexpected renderer state: $renderer" }
            if (screen.renderBackend == RenderBackend.OPENGL)
                check(renderer.fullFrameUploads == 0L) { "OpenGL uploaded full frames: $renderer" }
            session.capture("preview-list") { SuitePixels.requireContent(it, "preview-list") }
        }
        script.until("the list capture") { session.capturesIdle }
        pass(AcceptanceStep.LIST) { "$allocations surface allocations" }
    }

    private fun nativeItems() {
        script.act("open the 10k native item page") {
            marker = true
            post {
                screen.model.page = DemoPage.Items
                screen.itemBrowser.count = 10000
            }
        }
        script.until("native items prepared", 20_000) {
            val items = screen.nativeItemStatistics
            model { page == DemoPage.Items } &&
                ComposeThread.call {
                    screen.itemBrowser.previewBounds != null &&
                        screen.itemBrowser.edgeTooltipBounds != null &&
                        screen.itemBrowser.visibleCells.keys.containsAll(listOf(1, 8))
                } &&
                items.activeVariants > 0 &&
                items.pendingImages == 0 &&
                items.cachedImages >= items.activeVariants
        }
        script.pause(150)
        script.act("capture the native item page") { captureItems("preview-items") }
        script.until("the native item capture") { session.capturesIdle }
        script.act("click the first visible item") {
            val (x, y, index) =
                ComposeThread.call {
                    Triple(screen.itemBrowser.hitX, screen.itemBrowser.hitY, screen.itemBrowser.firstVisible)
                }
            firstVisible = index
            click(gui(x, y))
        }
        script.until("native item grid hit testing") { selected() == firstVisible }
        pass(AcceptanceStep.NATIVE_ITEMS) { screen.nativeItemStatistics.toString() }
    }

    private fun nativeTooltips() {
        script.act("start the native tooltip sequence") {
            check(minecraft.window.width == 1000 && minecraft.window.height == 720) {
                "Tooltip placement expects a 1000x720 framebuffer"
            }
            tooltips = NativeTooltipProbe(session)
        }
        script.until("the native tooltip sequence", 300_000) {
            val done = checkNotNull(tooltips).advance(screen)
            if (done) tooltips = null
            done
        }
        script.until("the tooltip captures") { session.capturesIdle }
        pass(AcceptanceStep.TOOLTIPS)
    }

    private fun nativeScroll() {
        script.act("remember native preparation") { prepared = screen.nativeItemStatistics.preparedImages }
        for (row in listOf(64, 128, 192)) {
            script.act("scroll the item grid to $row") {
                firstVisible = ComposeThread.call { screen.itemBrowser.firstVisible }
                post { screen.itemBrowser.scrollTarget = row }
            }
            script.until("the item grid reached $row") {
                ComposeThread.call { screen.itemBrowser.firstVisible } > firstVisible &&
                    screen.nativeItemStatistics.pendingImages == 0
            }
        }
        script.pause(150)
        script.act("check bounded native preparation") {
            val items = screen.nativeItemStatistics
            check(items.cachedImages <= 128 && items.preparedImages - prepared < 600) {
                "Native item work is not bounded: $items"
            }
            check(items.retiredImages > 0) { "Native item scrolling never exercised retirement: $items" }
            check(items.pendingImages == 0) { "Native items are still pending: $items" }
            if (screen.renderBackend == RenderBackend.OPENGL) {
                val renderer = screen.rendererStatistics
                check(renderer.nativeImageReadbacks == 0L && renderer.nativeImageCopies > 0L) {
                    "OpenGL native images left the GPU: $renderer"
                }
            }
            captureItems("preview-items-scrolled")
        }
        script.until("the scrolled capture") { session.capturesIdle }
        pass(AcceptanceStep.NATIVE_SCROLL) { screen.nativeItemStatistics.toString() }
    }

    private fun reloadResources() {
        script.act("reload resources") {
            epoch = SuitePlatform.resourceEpoch
            allocations = screen.rendererStatistics.surfaceAllocations
            reload = minecraft.reloadResourcePacks()
        }
        script.until("the resource reload", 120_000) { reload?.isDone == true }
        script.act("finish the reload") { checkNotNull(reload).join() }
        script.until("native items prepared after the reload", 20_000) {
            screen.nativeItemStatistics.let { it.cachedImages > 0 && it.pendingImages == 0 }
        }
        script.pause(150)
        script.act("check the reloaded session") {
            check(SuitePlatform.resourceEpoch > epoch) { "The resource reload listener did not run" }
            requireSession("Resource reload")
            val renderer = screen.rendererStatistics
            check(renderer.surfaceAllocations == allocations + 1 && renderer.liveSurfaces == 1) {
                "Resource reload did not rebuild exactly one retained surface: $renderer"
            }
            captureItems("preview-reloaded")
        }
        script.until("the reloaded capture") { session.capturesIdle }
        pass(AcceptanceStep.RELOAD)
    }

    private fun focus() {
        script.act("scroll the item grid back to the top") {
            screen.mouseMoved(-10.0, -10.0)
            post { screen.itemBrowser.scrollTarget = 0 }
        }
        script.until("items 1 and 8 laid out") {
            ComposeThread.call { screen.itemBrowser.visibleCells.keys.containsAll(listOf(1, 8)) }
        }
        script.act("hover item 1") { move(cell(1)) }
        script.until("the tooltip appeared while focused") { tooltipVisible() }
        script.act("lose logical window focus") { SuiteEnvironment.focused = false }
        script.until("focus loss hid the tooltip") { !tooltipVisible() }
        script.act("click and hover while unfocused") {
            selectedBefore = selected()
            check(selectedBefore != 1) { "Focus check needs a selection other than item 1" }
            click(cell(1))
            move(cell(8))
        }
        script.pause(1_000)
        script.act("check that unfocused input was ignored") {
            check(selected() == selectedBefore) { "Unfocused UI accepted a click" }
            check(!tooltipVisible()) { "Unfocused UI showed a tooltip" }
            SuiteEnvironment.focused = true
        }
        script.act("hover item 1 after focus returned") { move(cell(1)) }
        script.until("the tooltip returned with focus") { tooltipVisible() }
        script.act("click item 1") { click(cell(1)) }
        script.until("focused input selected item 1") { selected() == 1 }
        script.act("leave the grid") { screen.mouseMoved(-10.0, -10.0) }
        pass(AcceptanceStep.FOCUS)
    }

    private fun closePreview() {
        script.act("close the preview") {
            marker = false
            screen.onClose()
            check(SuitePlatform.screen === parent) { "Closing the preview did not return to its parent" }
            session.adopt(parent)
            check(first.state == SessionState.CLOSED) { "Close did not release the Compose session" }
            check(!screen.nativeTooltipStatistics.visible) { "A tooltip survived close" }
            check(screen.nativeItemStatistics.cachedImages == 0) {
                "Native item images survived close: ${screen.nativeItemStatistics}"
            }
            session.requireReleased(screen)
        }
        pass(AcceptanceStep.CLOSE)
    }

    private fun reopenPreview() {
        script.act("press F8 again") { adoptPreview("F8 did not reopen the preview") }
        script.until("the reopened preview rendered") { screen.rendererStatistics.renderedFrames > 0 }
        script.act("capture the reopened preview") {
            session.capture("preview-reopened") { SuitePixels.requireContent(it, "preview-reopened") }
        }
        script.until("the reopened capture") { session.capturesIdle }
        script.act("close with Escape") {
            key(SuitePlatform.KEY_ESCAPE)
            check(SuitePlatform.screen === parent) { "Escape did not close the reopened preview" }
            session.adopt(parent)
            session.requireReleased(screen)
        }
        pass(AcceptanceStep.REOPEN)
    }

    private fun components() {
        for (page in oreComponentPages) {
            lateinit var pageScreen: ComposePreviewScreen
            lateinit var exercise: OreComponentExercise
            var advances = 0
            script.act("open the ${page.name} page") {
                pageScreen = ComposePreviewScreen(parent)
                ComposeThread.call { pageScreen.model.page = page }
                exercise = OreComponentExercise(pageScreen.model, page)
                session.open(pageScreen)
            }
            script.until("the ${page.name} exercise", 30_000) {
                exercise.advance(checkNotNull(pageScreen.session) { "The ${page.name} page closed early" })
                ++advances >= 90 && exercise.complete
            }
            script.act("verify the ${page.name} page") { exercise.verify() }
        }
        script.act("return to the parent screen") { session.open(parent) }
        pass(AcceptanceStep.COMPONENTS) { "${oreComponentPages.size} pages" }
    }

    private fun stress() {
        repeat(12) { cycle ->
            lateinit var next: ComposePreviewScreen
            script.act("stress cycle ${cycle + 1}: open") {
                next = ComposePreviewScreen(parent)
                session.open(next)
            }
            script.until("stress cycle ${cycle + 1}: render") {
                next.rendererStatistics.renderedFrames > 0 && next.rendererStatistics.liveSurfaces == 1
            }
            script.act("stress cycle ${cycle + 1}: close") {
                val ui = checkNotNull(next.session) { "Stress screen ${cycle + 1} has no session" }
                next.onClose()
                check(ui.state == SessionState.CLOSED) { "Stress cycle ${cycle + 1} kept its session open" }
                check(SuitePlatform.screen === parent) { "Stress cycle ${cycle + 1} did not return to its parent" }
                session.adopt(parent)
                session.requireReleased(next)
            }
        }
        pass(AcceptanceStep.STRESS) { "12 cycles" }
    }

    private fun pass(step: AcceptanceStep, detail: () -> String? = { null }) =
        script.act("record $step") { log.pass(step, detail()) }

    private fun adoptPreview(failure: String) {
        check(SuitePlatform.pressPreviewKey(parent)) { "F8 was not consumed by the development key mapping" }
        screen = SuitePlatform.screen as? ComposePreviewScreen ?: error(failure)
        session.adopt(screen)
    }

    private fun requireSession(change: String) =
        check(screen.session === first) { "$change replaced the Compose session" }

    private fun <T> model(block: DemoModel.() -> T): T = ComposeThread.call { screen.model.block() }

    private fun post(block: () -> Unit) =
        checkNotNull(screen.session) { "The preview session is closed" }.post(Runnable(block))

    private fun selected() = ComposeThread.call { screen.itemBrowser.selected }

    private fun tooltipVisible() =
        screen.nativeTooltipStatistics.visible && screen.nativeTooltipBounds?.isEmpty == false

    private fun gui(x: Float, y: Float) =
        x.toDouble() * screen.width / minecraft.window.width to y.toDouble() * screen.height / minecraft.window.height

    private fun control(id: String) =
        ComposeThread.call { checkNotNull(screen.model.bounds[id]) { "Missing preview control $id" }.center }
            .let { gui(it.x, it.y) }

    private fun cell(index: Int) =
        ComposeThread.call {
                checkNotNull(screen.itemBrowser.visibleCells[index]) { "Item cell $index is not laid out" }
            }
            .let { gui(it.center.x, it.top + 30f) }

    private fun move(point: Pair<Double, Double>) = screen.mouseMoved(point.first, point.second)

    private fun click(point: Pair<Double, Double>) {
        screen.mouseMoved(point.first, point.second)
        screen.mouseClicked(point.first, point.second, SuitePlatform.MOUSE_LEFT)
        screen.mouseReleased(point.first, point.second, SuitePlatform.MOUSE_LEFT)
    }

    private fun key(key: Int, modifiers: Int = 0) {
        screen.keyPressed(key, 0, modifiers)
        screen.keyReleased(key, 0, modifiers)
    }

    /**
     * Checks the Minecraft marker and the rotated/clipped chest, which must match across repaint, scroll and reload.
     */
    private fun captureItems(name: String) {
        val bounds = ComposeThread.call {
            checkNotNull(screen.itemBrowser.previewBounds) { "Native item preview bounds are missing" }
        }
        val guiWidth = screen.width
        session.capture(name) { pixels ->
            SuitePixels.requireContent(pixels, name, minimumColors = 20)
            SuitePixels.requireMarker(pixels, name, guiWidth)
            val region =
                SuitePixels.previewRegion(
                    pixels,
                    name,
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.width.toInt(),
                    bounds.height.toInt(),
                )
            val previous = reference
            if (previous == null) reference = region else SuitePixels.requireSameRegion(previous, region, name)
        }
    }

    private companion object {
        const val TEXT = "Compose MC 界面 😀"
        const val COMPOSING = "拼音"
    }
}
