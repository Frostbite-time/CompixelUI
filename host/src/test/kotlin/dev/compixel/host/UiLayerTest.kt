package dev.compixel.host

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.compixel.platform.KeyInput
import dev.compixel.platform.Modifiers
import dev.compixel.platform.MouseButton
import dev.compixel.platform.UiKey
import dev.compixel.platform.UiThread
import dev.compixel.render.FrameRenderer
import dev.compixel.render.RecordedFrame
import dev.compixel.render.RenderBackend
import dev.compixel.render.RendererStatistics
import dev.compixel.render.UiFrameProfiler
import dev.compixel.ui.CtrlOrCmd
import dev.compixel.ui.LocalOverlayVisibility
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.UiDesign
import dev.compixel.ui.UiFeedback
import dev.compixel.ui.theme.ColorSchema
import dev.compixel.ui.theme.LocalColorEditor
import dev.compixel.ui.theme.LocalSchemes
import dev.compixel.ui.theme.SchemeCatalog
import dev.compixel.ui.theme.SchemeFile
import dev.compixel.ui.theme.SchemeId
import dev.compixel.ui.theme.SchemeOwner
import dev.compixel.ui.theme.SchemeSettings
import dev.compixel.ui.theme.Schemes
import java.util.concurrent.CountDownLatch
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

        override fun laidOut(frame: Long) {
            generations += frame
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

    private var schemes: Schemes? = null
    private var editor: (() -> Unit)? = null
    private var feedback: UiFeedback? = null

    private object TestColors : ColorSchema("test") {
        val panel = color("panel", "surfaces", androidx.compose.ui.graphics.Color.Black)
    }

    private val design =
        object : UiDesign {
            override val owner = SchemeOwner("test", TestColors)

            @Composable
            override fun Decorate(content: @Composable () -> Unit) {
                val schemes = LocalSchemes.current
                val editor = LocalColorEditor.current
                val feedback = LocalUiFeedback.current
                SideEffect {
                    this@UiLayerTest.schemes = schemes
                    this@UiLayerTest.editor = editor
                    this@UiLayerTest.feedback = feedback
                }
                content()
            }

            override fun preview(): @Composable () -> Unit = {}
        }

    private inner class TestLayer(
        contentState: UiStateBinding,
        private val failItemsClose: Boolean = false,
        closeHost: () -> Unit = {},
        openColorEditor: (() -> Unit)? = null,
        content: @Composable () -> Unit = {},
    ) :
        UiLayer<Unit, String>(
            RenderBackend.CPU_RASTER,
            guiUnitsPerDp = 1f,
            minimumUiDensity = 0f,
            design = design,
            windowFocused = { true },
            prepareFrameContent = { false },
            contentState = contentState,
            closeHost = closeHost,
            openColorEditor = openColorEditor,
            content = content,
        ) {
        lateinit var renderer: FakeRenderer
        lateinit var items: FakeImages
        lateinit var tooltips: FakeImages
        var feedbackPlayed = 0
        var epoch = 0L
        var currentSchemes = Schemes.Empty
        val warnings = mutableListOf<String>()

        override val thread = UiThread.current()

        override val framebufferWidth = 200
        override val framebufferHeight = 100
        override val guiScale = 1f
        var clipboardText = ""
        var clipboardReads = 0

        override var systemClipboard: String
            get() {
                clipboardReads++
                return clipboardText
            }
            set(value) {
                clipboardText = value
            }

        override fun playFeedback() {
            feedbackPlayed++
        }

        override fun pointerModifiers() = Modifiers()

        override val resourceEpoch
            get() = epoch

        override val schemes
            get() = currentSchemes

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

    private fun state() = UiStateBinding()

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
    fun `images that change while preparing are drawn by the same frame`() {
        val layer = TestLayer(state())
        layer.open(200, 100)
        layer.items.changes += true
        log.clear()
        layer.prepare(Unit, 200, 100)
        // One layout tells the image sources what the frame shows, and one drawing shows what they prepared.
        assertEquals(listOf(1L, 1L), layer.items.generations)
        assertEquals(layer.items.generations, layer.tooltips.generations)
        assertEquals(listOf("render"), log)
        layer.render(Unit, 200, 100)
        layer.items.changes += true
        layer.render(Unit, 200, 100)
        layer.render(Unit, 200, 100)
        assertEquals(listOf(1L, 1L, 2L), layer.items.generations, "A frame without changes asked for its images again")
        layer.close()
    }

    @Test
    fun `tasks posted during an input event run before it returns and a close request closes the host`() {
        val handled = mutableListOf<Int>()
        val contentState = state()
        var closes = 0
        val layer = TestLayer(contentState, closeHost = { closes++ })
        layer.open(200, 100)
        assertTrue(contentState.gameThread.post { handled += 3 })
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
    fun `content posts through LocalGameThread and its game state refreshes before the input event returns`() {
        var energy = 1
        val contentState = state()
        var thread: GameThread? = null
        var observed = 0
        val layer =
            TestLayer(
                contentState,
                content = {
                    val current = LocalGameThread.current
                    val value by gameState(initialValue = 0) { energy }
                    val shown = value
                    SideEffect {
                        thread = current
                        observed = shown
                    }
                },
            )
        layer.open(200, 100)
        layer.render(Unit, 200, 100)
        assertEquals(0, observed)
        layer.tickContent()
        layer.render(Unit, 200, 100)
        assertEquals(1, observed)
        assertTrue(checkNotNull(thread).post { energy = 4 })
        assertTrue(layer.handled(true))
        assertEquals(4, energy)
        layer.render(Unit, 200, 100)
        assertEquals(4, observed, "The task's change waited for a tick")
        layer.close()
    }

    @Test
    fun `refused tasks are reported once per session`() {
        val contentState = state()
        val layer = TestLayer(contentState)
        layer.open(200, 100)
        repeat(70) { contentState.gameThread.post {} }
        layer.tick()
        layer.tick()
        assertEquals(1, layer.warnings.size)
        assertContains(layer.warnings.single(), "6 were rejected")
        layer.close()
        layer.open(200, 100)
        repeat(70) { contentState.gameThread.post {} }
        layer.tick()
        assertEquals(2, layer.warnings.size)
        layer.close()
    }

    @Test
    fun `content gets the current schemes, the color editor and feedback, which act on the game thread`() {
        var opened = 0
        val layer = TestLayer(state(), openColorEditor = { opened++ })
        layer.open(200, 100)
        layer.render(Unit, 200, 100)
        assertSame(Schemes.Empty, schemes)
        val catalog =
            SchemeCatalog.create(
                mapOf(SchemeId("test", "x") to listOf(SchemeFile("test", mapOf("format" to 1)))),
                emptyMap(),
            )
        val reloaded = Schemes(catalog, SchemeSettings())
        layer.currentSchemes = reloaded
        layer.render(Unit, 200, 100)
        assertSame(reloaded, schemes)
        checkNotNull(editor).invoke()
        assertEquals(0, opened)
        layer.tickContent()
        assertEquals(1, opened)
        checkNotNull(feedback).activate()
        assertEquals(0, layer.feedbackPlayed)
        layer.tick()
        layer.tick()
        assertEquals(1, layer.feedbackPlayed)
        layer.close()
        // A host that cannot open the editor, such as a HUD layer, offers none.
        val hud = TestLayer(state())
        hud.open(200, 100)
        hud.render(Unit, 200, 100)
        assertNull(editor)
        hud.close()
    }

    // What a ScreenTransition's content saw, written while it composed.
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
        assertFalse(contentState.gameThread.post {})
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
    fun `an entrance starts when the first frame shows, however long that took`() {
        val shown = Shown()
        val layer = TestLayer(state(), content = transition(shown))
        layer.open(200, 100)
        // A container screen lays its content out while it initializes; its first frame may come much later.
        layer.layout()
        Thread.sleep(60)
        layer.render(Unit, 200, 100)
        val presented = System.nanoTime()
        frames(layer) { shown.phase == EnterExitState.Visible }
        assertTrue(System.nanoTime() - presented >= 35_000_000L, "The 40 ms entrance ran before the first frame showed")
        layer.close()
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
    fun `a covered session shows again as it was, with its game state read anew and without entering again`() {
        var reads = 0
        val contentState = state()
        val read by contentState.gameState(intervalTicks = 1000) { ++reads }
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
        assertEquals(2, reads)
        assertEquals(2, read)
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
        val open = mutableStateOf(false)
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
        open.value = true
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
    fun `a paste reads the system clipboard as it is then, and other keys read nothing`() {
        // Compose's text shortcuts use CtrlOrCmd: Command on macOS, Control elsewhere.
        val shortcut = if (CtrlOrCmd.isCmd) Modifiers(meta = true) else Modifiers(control = true)
        val value = mutableStateOf(TextFieldValue(""))
        val pasted = CountDownLatch(1)
        val layer =
            TestLayer(
                state(),
                content = {
                    BasicTextField(
                        value.value,
                        {
                            value.value = it
                            if (it.text == "copied later") pasted.countDown()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        layer.open(200, 100)
        layer.render(Unit, 200, 100)
        layer.press(10.0, 10.0, MouseButton.LEFT)
        layer.release(10.0, 10.0, MouseButton.LEFT)
        layer.render(Unit, 200, 100)
        val reads = layer.clipboardReads
        // A held key repeats its press many times a second.
        val held =
            listOf(
                KeyInput(UiKey.SHIFT_LEFT, true, Modifiers(shift = true)),
                KeyInput(UiKey.CTRL_LEFT, true, Modifiers(control = true)),
                KeyInput(UiKey.SPACE, true),
                KeyInput(UiKey.V, true),
            )
        held.forEach { input ->
            repeat(20) { layer.key(input) }
            layer.key(input.copy(pressed = false))
        }
        assertEquals(reads, layer.clipboardReads)
        layer.clipboardText = "copied later"
        val paste = KeyInput(UiKey.V, true, shortcut)
        layer.key(paste)
        // Compose pastes from a coroutine, in a later frame.
        val deadline = System.nanoTime() + 2_000_000_000
        while (pasted.count > 0 && System.nanoTime() < deadline) {
            layer.render(Unit, 200, 100)
            Thread.sleep(5)
        }
        assertEquals(0, pasted.count, "The paste did not read text copied after the screen opened")
        assertTrue(layer.clipboardReads > reads)
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
