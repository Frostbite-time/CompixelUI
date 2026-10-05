package dev.compixel.host

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.compixel.bridge.ComposeThread
import dev.compixel.platform.Modifiers
import dev.compixel.render.FrameRenderer
import dev.compixel.render.RecordedFrame
import dev.compixel.render.RenderBackend
import dev.compixel.render.RendererStatistics
import dev.compixel.render.UiFrameProfiler
import dev.compixel.ui.LocalOverlayVisibility
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
        content: @Composable () -> Unit = {},
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
            content = content,
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

    // What a ScreenTransition's content saw, written on the Compose thread.
    private class Shown {
        @Volatile var phase: EnterExitState? = null
        val targets = java.util.concurrent.CopyOnWriteArrayList<EnterExitState>()
        val phases = java.util.concurrent.CopyOnWriteArrayList<EnterExitState>()
        val compositions = java.util.concurrent.CopyOnWriteArrayList<Any>()
    }

    private fun transition(shown: Shown): @Composable () -> Unit = {
        ScreenTransition(enter = fadeIn(tween(40)), exit = fadeOut(tween(40))) {
            Box(Modifier.size(20.dp))
            val composition = remember { Any() }
            val phase = transition.currentState
            val target = transition.targetState
            SideEffect {
                shown.phase = phase
                shown.phases += phase
                shown.targets += target
                shown.compositions += composition
            }
        }
    }

    // Presents frames on real time, as a host does every frame, until [done] holds.
    private fun frames(layer: TestLayer, done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!done()) {
            check(System.nanoTime() < end) { "The layer did not settle" }
            layer.render(Unit, 200, 100)
            Thread.sleep(5)
        }
    }

    @Test
    fun `an exit plays without the content's state after the host closes, until the layer can close`() {
        val contentState = state()
        val shown = Shown()
        val layer = TestLayer(contentState, content = transition(shown))
        val opened = System.nanoTime()
        layer.open(200, 100)
        frames(layer) { shown.phase == EnterExitState.Visible }
        // The content composed before the first frame, which still plays its 40 ms entrance.
        assertTrue(System.nanoTime() - opened >= 35_000_000L, "The entrance ended at once")
        val started = System.nanoTime()
        assertTrue(layer.exit())
        assertTrue(layer.exiting)
        assertFalse(layer.exitFinished)
        assertFalse(contentState.isOpen)
        assertFalse(contentState.send(1))
        assertFalse(layer.hasTextInputFocus)
        frames(layer) { layer.exitFinished }
        // The exit takes its 40 ms on the frame clock.
        assertTrue(System.nanoTime() - started >= 35_000_000L, "The exit finished early")
        assertEquals(EnterExitState.PostExit, shown.targets.last())
        assertNotNull(layer.session)
        log.clear()
        layer.close()
        assertNull(layer.session)
        assertFalse(layer.exiting)
        assertContains(log, "close renderer")
    }

    @Test
    fun `without a ScreenTransition the layer closes at once when its host closes`() {
        val layer = TestLayer(state())
        layer.open(200, 100)
        layer.render(Unit, 200, 100)
        assertFalse(layer.exit())
        assertNull(layer.session)
        assertFalse(layer.exiting)
    }

    @Test
    fun `a covered session shows again as it was, from a new snapshot and without entering again`() {
        var snapshots = 0
        val contentState = UiStateBinding<Int, Int>({ ++snapshots }) {}
        val shown = Shown()
        val layer = TestLayer(contentState, content = transition(shown))
        layer.open(200, 100)
        frames(layer) { shown.phase == EnterExitState.Visible }
        val session = layer.session
        val entered = shown.phases.size
        layer.suspend()
        assertTrue(layer.suspended)
        assertTrue(contentState.isOpen)
        assertFalse(layer.press(10.0, 10.0, null))
        layer.open(200, 100)
        assertFalse(layer.suspended)
        assertSame(session, layer.session)
        assertEquals(2, snapshots)
        repeat(5) {
            layer.render(Unit, 200, 100)
            Thread.sleep(5)
        }
        assertTrue(shown.phases.drop(entered).all { it == EnterExitState.Visible })
        assertTrue(shown.compositions.all { it === shown.compositions.first() })
        // A covered session has nothing to show, so closing for good releases it at once.
        layer.suspend()
        assertFalse(layer.exit())
        assertNull(layer.session)
    }

    @Test
    fun `a host that shows again before its exit finished starts a new session`() {
        val shown = Shown()
        val layer = TestLayer(state(), content = transition(shown))
        layer.open(200, 100)
        frames(layer) { shown.phase == EnterExitState.Visible }
        val exited = layer.session
        assertTrue(layer.exit())
        layer.open(200, 100)
        assertFalse(layer.exiting)
        assertNotNull(layer.session)
        assertNotSame(exited, layer.session)
        layer.close()
    }

    @Test
    fun `a popup opened while the screen shows appears at once and fades with its exit, which waits for it`() {
        val shown = Shown()
        val open = ComposeThread.call { mutableStateOf(false) }
        val drawn = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val layer =
            TestLayer(
                state(),
                content = {
                    ScreenTransition(enter = fadeIn(tween(20)), exit = fadeOut(tween(20))) {
                        val phase = transition.currentState
                        SideEffect { shown.phase = phase }
                        if (open.value)
                            Popup {
                                val visibility = checkNotNull(LocalOverlayVisibility.current).animate()
                                Box(Modifier.size(10.dp).drawBehind { drawn += visibility.value })
                            }
                    }
                },
            )
        layer.open(200, 100)
        frames(layer) { shown.phase == EnterExitState.Visible }
        ComposeThread.call { open.value = true }
        frames(layer) { drawn.isNotEmpty() }
        assertEquals(1f, drawn.first(), "A popup opened while the screen shows entered")
        val started = System.nanoTime()
        assertTrue(layer.exit())
        frames(layer) { layer.exitFinished }
        // The content's exit takes 20 ms, the popup's fade 150 ms.
        assertTrue(System.nanoTime() - started >= 140_000_000L, "The screen closed before its popup faded")
        assertTrue(drawn.any { it > 0f && it < 1f }, "The popup did not fade: $drawn")
        layer.close()
    }

    @Test
    fun `a screen with several transitions closes after the longest exit`() {
        val backdrop = Shown()
        val window = Shown()
        val layer =
            TestLayer(
                state(),
                content = {
                    ScreenTransition(exit = fadeOut(tween(20))) {
                        val phase = transition.currentState
                        SideEffect { backdrop.phase = phase }
                    }
                    ScreenTransition(exit = fadeOut(tween(80))) {
                        val phase = transition.currentState
                        SideEffect { window.phase = phase }
                    }
                },
            )
        layer.open(200, 100)
        frames(layer) { backdrop.phase == EnterExitState.Visible && window.phase == EnterExitState.Visible }
        val started = System.nanoTime()
        assertTrue(layer.exit())
        frames(layer) { layer.exitFinished }
        assertTrue(System.nanoTime() - started >= 75_000_000L, "The screen closed before its longest exit finished")
        layer.close()
    }
}
