package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import dev.compixel.bridge.ComposeThread
import dev.compixel.platform.Modifiers
import dev.compixel.render.FrameRenderer
import dev.compixel.render.RecordedFrame
import dev.compixel.render.RenderBackend
import dev.compixel.render.RendererStatistics
import dev.compixel.render.UiFrameProfiler
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.UiDesign
import dev.compixel.ui.UiFeedback
import dev.compixel.ui.theme.LocalThemeCatalog
import dev.compixel.ui.theme.ThemeCatalog
import dev.compixel.ui.theme.ThemeId
import dev.compixel.ui.theme.ThemeLayer
import kotlin.test.*
import org.junit.jupiter.api.Test

class UiLayerTest {
    private val log = mutableListOf<String>()

    private inner class FakeRenderer : FrameRenderer<String> {
        var rendered = 0L

        override val statistics
            get() = RendererStatistics(RenderBackend.CPU_RASTER, rendered, 0, 0)

        override val needsFrame = false

        override fun render(frame: RecordedFrame) {
            rendered++
            log += "render"
        }

        override fun present(destination: String) {
            log += "present $destination"
        }

        override fun reset() {
            log += "reset renderer"
        }

        override fun close() {
            log += "close renderer"
        }
    }

    private inner class FakeImages(private val name: String, private val failClose: Boolean = false) :
        NativeImageSource {
        val changes = ArrayDeque<Boolean>()
        val generations = mutableListOf<Long>()

        override fun recorded(frameGeneration: Long) {
            generations += frameGeneration
        }

        override fun prepare(now: Long, current: ScreenMetrics): Boolean = changes.removeFirstOrNull() ?: false

        override fun reset() {
            log += "reset $name"
        }

        override fun close() {
            log += "close $name"
            if (failClose) error("$name failed to close")
        }
    }

    private var theme: ThemeId? = null
    private var catalog: ThemeCatalog? = null
    private var feedback: UiFeedback? = null

    private val design =
        object : UiDesign {
            @Composable
            override fun Decorate(theme: ThemeId, content: @Composable () -> Unit) {
                val catalog = LocalThemeCatalog.current
                val feedback = LocalUiFeedback.current
                SideEffect {
                    this@UiLayerTest.theme = theme
                    this@UiLayerTest.catalog = catalog
                    this@UiLayerTest.feedback = feedback
                }
                content()
            }
        }

    private inner class TestLayer(
        contentState: UiStateBinding<*, *>,
        private val failItemsClose: Boolean = false,
        closeHost: () -> Unit = {},
    ) :
        UiLayer<Unit, String>(
            RenderBackend.CPU_RASTER,
            guiUnitsPerDp = 1f,
            minimumUiDensity = 0f,
            theme = ThemeId("test", "screen"),
            design = design,
            windowFocused = { true },
            prepareFrameContent = { false },
            contentState = contentState,
            closeHost = closeHost,
            content = {},
        ) {
        lateinit var renderer: FakeRenderer
        lateinit var items: FakeImages
        lateinit var tooltips: FakeImages
        var feedbackPlayed = 0
        var epoch = 0L
        var currentThemes = ThemeCatalog.Empty
        val warnings = mutableListOf<String>()

        override fun assertRenderThread() {}

        override val framebufferWidth = 200
        override val framebufferHeight = 100
        override val guiScale = 1f
        override var systemClipboard = ""

        override fun playFeedback() {
            feedbackPlayed++
        }

        override fun pointerModifiers() = Modifiers()

        override val resourceEpoch
            get() = epoch

        override val themes
            get() = currentThemes

        override fun warn(message: String) {
            warnings += message
        }

        override fun createSurface(profiler: UiFrameProfiler?): UiLayerSurface<String> {
            renderer = FakeRenderer()
            items = FakeImages("items", failItemsClose)
            tooltips = FakeImages("tooltips")
            return UiLayerSurface(renderer, items, tooltips, FakeImages("drawings"))
        }

        override fun destination(graphics: Unit, metrics: ScreenMetrics) = "${metrics.guiWidth}x${metrics.guiHeight}"
    }

    private fun state(handled: MutableList<Int> = mutableListOf()) =
        UiStateBinding<Int, Int>({ handled.sum() }) {
            handled += it
        }

    @Test
    fun `a session closes its native images and then its renderer even when one of them fails`() {
        val layer = TestLayer(state(), failItemsClose = true)
        layer.open(200, 100)
        layer.prepare(Unit, 200, 100)
        layer.render(Unit, 200, 100)
        log.clear()
        assertFailsWith<IllegalStateException> { layer.close() }
        assertEquals(listOf("close items", "close tooltips", "close drawings", "close renderer"), log)
        assertNull(layer.session)
        // The final statistics stay readable after the renderer closed.
        assertEquals(1, layer.rendererStatistics.renderedFrames)
    }

    @Test
    fun `prepared frames are presented once and a resource reset clears images before the renderer`() {
        val layer = TestLayer(state())
        layer.open(200, 100)
        layer.prepare(Unit, 200, 100)
        assertTrue(layer.framePrepared)
        layer.render(Unit, 200, 100)
        assertFalse(layer.framePrepared)
        assertEquals(listOf("render", "present 200x100"), log)
        log.clear()
        layer.epoch++
        layer.render(Unit, 200, 100)
        assertEquals(
            listOf("reset drawings", "reset items", "reset tooltips", "reset renderer", "present 200x100"),
            log,
        )
        layer.close()
    }

    @Test
    fun `images that change while preparing record the frame again`() {
        val layer = TestLayer(state())
        layer.open(200, 100)
        layer.items.changes += true
        layer.prepare(Unit, 200, 100)
        assertEquals(2, layer.items.generations.size)
        assertTrue(layer.items.generations[1] > layer.items.generations[0])
        assertEquals(layer.items.generations, layer.tooltips.generations)
        layer.close()
    }

    @Test
    fun `actions sent during an input event run before it returns and a close request closes the host`() {
        val handled = mutableListOf<Int>()
        val contentState = state(handled)
        var closes = 0
        val layer = TestLayer(contentState, closeHost = { closes++ })
        layer.open(200, 100)
        assertTrue(contentState.send(3))
        assertFalse(layer.handled(false))
        assertEquals(listOf(3), handled)
        layer.requestClose()
        layer.tickContent()
        assertEquals(1, closes)
        // A request ends with the session that made it.
        layer.requestClose()
        layer.close()
        layer.open(200, 100)
        layer.tickContent()
        assertEquals(1, closes)
        layer.close()
        // A closed session takes nothing more from the event.
        assertTrue(layer.handled(false))
    }

    @Test
    fun `refused actions are reported once per session`() {
        val contentState = state()
        val layer = TestLayer(contentState)
        layer.open(200, 100)
        repeat(70) { contentState.send(1) }
        layer.tick()
        layer.tick()
        assertEquals(1, layer.warnings.size)
        assertContains(layer.warnings.single(), "6 were rejected")
        layer.close()
        layer.open(200, 100)
        repeat(70) { contentState.send(1) }
        layer.tick()
        assertEquals(2, layer.warnings.size)
        layer.close()
    }

    @Test
    fun `content gets the theme, the current theme files and feedback played on the game thread`() {
        val layer = TestLayer(state())
        layer.open(200, 100)
        layer.render(Unit, 200, 100)
        assertEquals(ThemeId("test", "screen"), theme)
        assertEquals(ThemeCatalog.Empty, catalog)
        val reloaded = ThemeCatalog.create(mapOf(ThemeId("test") to listOf(ThemeLayer("test", mapOf("format" to 1)))))
        layer.currentThemes = reloaded
        layer.render(Unit, 200, 100)
        assertSame(reloaded, catalog)
        ComposeThread.call { checkNotNull(feedback).activate() }
        assertEquals(0, layer.feedbackPlayed)
        layer.tick()
        layer.tick()
        assertEquals(1, layer.feedbackPlayed)
        layer.close()
    }
}
