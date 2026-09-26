package dev.composemc.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.composemc.bridge.ComposeThread
import dev.composemc.platform.*
import dev.composemc.platform.Viewport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test

class UiSessionTest {
    @Test
    fun firstFrameAndResizeIncludeLayoutDrivenComposition() {
        UiSession(Viewport(80, 60)) {
                var measured by remember { mutableStateOf(IntSize.Zero) }
                Box(Modifier.fillMaxSize().background(Color.Red).onSizeChanged { measured = it }) {
                    Box(
                        Modifier.offset((measured.width / 2).dp, (measured.height / 2).dp)
                            .size(8.dp)
                            .background(Color.Green)
                    )
                }
            }
            .use { session ->
                fun pixel(x: Int, y: Int, time: Long): Int =
                    checkNotNull(session.frame(time)).use { frame ->
                        javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frame.encodePng())).getRGB(x, y)
                    }
                assertEquals(
                    0xff00ff00.toInt(),
                    pixel(42, 32, 1_000_000),
                    "The first visible frame used pre-layout state",
                )
                session.resize(Viewport(120, 80))
                assertEquals(0xff00ff00.toInt(), pixel(62, 42, 2_000_000), "Resize exposed the old layout for a frame")
            }
    }

    @Test
    fun startupLayoutDoesNotWaitForAnInfiniteAnimationOrSpeedUpLaterFrames() {
        val animationFrames = AtomicInteger()
        UiSession(Viewport(80, 60)) {
                var measured by remember { mutableStateOf(IntSize.Zero) }
                LaunchedEffect(Unit) { while (true) withFrameNanos { animationFrames.incrementAndGet() } }
                Box(Modifier.fillMaxSize().onSizeChanged { measured = it }) {
                    Box(Modifier.size((measured.width / 2).dp))
                }
            }
            .use { session ->
                session.frame(1_000_000)?.close()
                val startupFrames = animationFrames.get()
                assertTrue(startupFrames in 1..4, "Startup did not bound its layout work")
                session.frame(2_000_000)?.close()
                assertEquals(startupFrames + 1, animationFrames.get())
                session.frame(3_000_000)?.close()
                assertEquals(startupFrames + 2, animationFrames.get())
            }
    }

    @Test
    fun concurrentProducersRespectCapacityAndFrameBudget() {
        val accepted = AtomicInteger()
        val executed = AtomicInteger()
        UiSession(Viewport(32, 32), commandCapacity = 16, commandsPerFrame = 4) {}
            .use { session ->
                val start = CountDownLatch(1)
                val producers =
                    List(8) {
                        Thread {
                            start.await()
                            repeat(32) {
                                if (session.post(Runnable { executed.incrementAndGet() })) accepted.incrementAndGet()
                            }
                        }
                            .also { it.start() }
                    }
                start.countDown()
                producers.forEach { it.join() }
                assertEquals(16, accepted.get())
                assertEquals(16, session.pendingCommands)
                session.frame(1_000_000)?.close()
                assertEquals(4, executed.get())
                assertEquals(12, session.pendingCommands)
                repeat(3) { session.frame(2_000_000L + it * 1_000_000)?.close() }
                assertEquals(16, executed.get())
                assertEquals(0, session.pendingCommands)
            }
    }

    @Test
    fun reentrantPostsWaitAndFailureDoesNotDiscardLaterCommands() {
        UiSession(Viewport(32, 32), commandCapacity = 8, commandsPerFrame = 4) {}
            .use { session ->
                val executed = AtomicInteger()
                lateinit var again: Runnable
                again = Runnable {
                    executed.incrementAndGet()
                    session.post(again)
                }
                session.post(again)
                session.frame(1_000_000)?.close()
                assertEquals(1, executed.get())
                assertEquals(1, session.pendingCommands)
                session.frame(2_000_000)?.close()
                assertEquals(2, executed.get())
            }
        UiSession(Viewport(32, 32), commandCapacity = 8, commandsPerFrame = 4) {}
            .use { session ->
                val executed = AtomicInteger()
                session.post(Runnable { throw IllegalArgumentException("consumer failure") })
                session.post(Runnable { executed.incrementAndGet() })
                assertFailsWith<IllegalArgumentException> { session.frame(1_000_000)?.close() }
                assertEquals(1, session.pendingCommands)
                session.frame(2_000_000)?.close()
                assertEquals(1, executed.get())
            }
    }

    @Test
    fun closingDropsQueuedWorkAndRejectsLaterProducers() {
        val session = UiSession(Viewport(32, 32), commandCapacity = 2, commandsPerFrame = 1) {}
        val executed = AtomicInteger()
        session.post(Runnable { executed.incrementAndGet() })
        session.close()
        val accepted = AtomicReference<Boolean>()
        Thread { accepted.set(session.post(Runnable { executed.incrementAndGet() })) }
            .also {
                it.start()
                it.join()
            }
        assertEquals(false, accepted.get())
        assertEquals(0, session.pendingCommands)
        assertEquals(0, executed.get())
    }

    @Test
    fun dispatchProfilingIsScopedAndSurvivesAnEdtException() {
        val profiler = dev.composemc.render.UiFrameProfiler()
        profiler.beginFrame()
        ComposeThread.traceCalls(profiler) {
            assertFailsWith<IllegalArgumentException> { ComposeThread.call { throw IllegalArgumentException("probe") } }
            assertTrue(ComposeThread.call { java.awt.EventQueue.isDispatchThread() })
        }
        profiler.endFrame()
        assertEquals(2, profiler.frames().single().composeCalls)
        profiler.beginFrame()
        ComposeThread.call { Unit }
        profiler.endFrame()
        assertEquals(0, profiler.frames().last().composeCalls, "Profiling escaped its caller scope")
    }

    @Test
    fun closeCancelsFrameAwaitersAndDelayedEffectsOnTheEdt() {
        val started = CountDownLatch(2)
        val cancelled = CountDownLatch(2)
        val wrongThread = AtomicReference<String?>()
        fun effectCancelled() {
            if (!java.awt.EventQueue.isDispatchThread()) wrongThread.set(Thread.currentThread().name)
            cancelled.countDown()
        }
        val session =
            UiSession(Viewport(64, 64)) {
                LaunchedEffect("frame") {
                    started.countDown()
                    try {
                        while (true) withFrameNanos {}
                    } finally {
                        effectCancelled()
                    }
                }
                LaunchedEffect("delay") {
                    started.countDown()
                    try {
                        delay(30_000)
                    } finally {
                        effectCancelled()
                    }
                }
            }
        try {
            session.frame(1_000_000)?.close()
            assertTrue(started.await(2, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS), "Effects survived session close")
        assertNull(wrongThread.get())
    }

    @Test
    fun `window blur cancels input until refocused and clicked again`() {
        val value = ComposeThread.call { mutableStateOf(TextFieldValue("")) }
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                session.frame(1_000_000)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.setFocused(false)
                assertFalse(session.hasTextInputFocus)
                assertFalse(session.commitText("ignored"))
                assertFalse(session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT)))
                session.setFocused(true)
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                assertTrue(session.hasTextInputFocus)
                assertTrue(session.commitText("active"))
                assertEquals("active", ComposeThread.call { value.value.text })
            }
    }

    @Test
    fun `frames and window focus refresh the last text input focus`() {
        val value = ComposeThread.call { mutableStateOf(TextFieldValue("")) }
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun click() {
                    session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                    session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                }
                session.frame(1_000_000)?.close()
                assertFalse(session.lastTextInputFocus)
                click()
                session.frame(2_000_000)?.close()
                assertTrue(session.lastTextInputFocus)
                session.setFocused(false)
                assertFalse(session.lastTextInputFocus)
                session.setFocused(true)
                click()
                session.frame(3_000_000)?.close()
                assertTrue(session.lastTextInputFocus)
                session.close()
                assertFalse(session.lastTextInputFocus)
            }
    }

    @Test
    fun `composing text shows in the field until committed or removed`() {
        val value = ComposeThread.call { mutableStateOf("") }
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun text() = ComposeThread.call { value.value }
                session.frame(1_000_000)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                assertFalse(session.setComposingText(null), "There was no composition to remove")
                assertTrue(session.setComposingText(ComposingText("pin")))
                assertEquals("pin", text())
                assertTrue(session.setComposingText(ComposingText("pinyin")))
                assertEquals("pinyin", text())
                assertTrue(session.commitText("拼音"))
                assertEquals("拼音", text())
                assertFalse(session.setComposingText(null), "Committed text ended the composition")
                assertTrue(session.setComposingText(ComposingText("ni")))
                assertTrue(session.setComposingText(null))
                assertEquals("拼音", text())
                assertTrue(session.setComposingText(ComposingText("hao")))
                session.setFocused(false)
                assertEquals("拼音", text(), "Window blur left the unconfirmed composition in the field")
            }
    }

    @Test
    fun `composition keeps the input method cursor and never deletes a selection when removed`() {
        val value = ComposeThread.call { mutableStateOf(TextFieldValue("")) }
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun current() = ComposeThread.call { value.value }
                session.frame(1_000_000)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                assertTrue(session.commitText("keep"))
                ComposeThread.call { value.value = value.value.copy(selection = TextRange(0, 4)) }
                session.frame(3_000_000)?.close()
                assertFalse(session.setComposingText(null))
                assertEquals("keep", current().text)
                assertTrue(session.setComposingText(ComposingText("xyz", cursor = 1)))
                assertEquals(TextFieldValue("xyz", TextRange(1), TextRange(0, 3)), current())
            }
    }

    @Test
    fun `state-based text fields take composing text too`() {
        val state = ComposeThread.call { TextFieldState() }
        UiSession(Viewport(320, 120)) {
                BasicTextField(state, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun text() = ComposeThread.call { state.text.toString() }
                session.frame(1_000_000)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                assertTrue(session.setComposingText(ComposingText("ni")))
                assertEquals("ni", text())
                assertTrue(session.commitText("你"))
                assertTrue(session.setComposingText(ComposingText("hao")))
                assertTrue(session.setComposingText(null))
                assertEquals("你", text())
                assertNotNull(session.frame(3_000_000)).close()
                assertNotNull(session.lastTextInputArea)
            }
    }

    @Test
    fun `the text input area follows the caret of the focused field`() {
        val value = ComposeThread.call { mutableStateOf("") }
        UiSession(Viewport(320, 120)) {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value.value,
                        { value.value = it },
                        modifier = Modifier.offset(40.dp, 30.dp).size(200.dp, 40.dp),
                    )
                }
            }
            .use { session ->
                session.frame(1_000_000)?.close()
                assertNull(session.lastTextInputArea)
                session.pointer(PointerInput(PointerAction.PRESS, 50f, 40f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 50f, 40f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                val empty = assertNotNull(session.lastTextInputArea)
                assertTrue(
                    empty.left >= 40f && empty.top >= 30f && empty.bottom <= 70f,
                    "Caret $empty lies outside the field",
                )
                assertTrue(session.commitText("abc"))
                session.frame(3_000_000)?.close()
                assertTrue(
                    assertNotNull(session.lastTextInputArea).left > empty.left,
                    "The area did not follow the caret",
                )
                session.setFocused(false)
                assertNull(session.lastTextInputArea)
            }
    }

    @Test
    fun `Escape dismisses Compose dialog before returning to the host`() {
        val visible = ComposeThread.call { mutableStateOf(true) }
        UiSession(Viewport(400, 300)) {
                if (visible.value)
                    Dialog(onDismissRequest = { visible.value = false }) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
            }
            .use { session ->
                session.frame(1_000_000)?.close()
                assertTrue(session.key(KeyInput(UiKey.ESCAPE, true)))
                assertFalse(ComposeThread.call { visible.value })
                session.frame(2_000_000)?.close()
                assertFalse(session.key(KeyInput(UiKey.ESCAPE, true)))
            }
    }

    @Test
    fun `consecutive select copy cut paste uses current text and selection`() {
        val value = ComposeThread.call { mutableStateOf(TextFieldValue("hello")) }
        val clipboard = MemoryClipboard()
        val pasted = CountDownLatch(1)
        UiSession(
                Viewport(320, 120),
                clipboard,
                content = {
                    BasicTextField(
                        value.value,
                        {
                            val replacesEmptyText = value.value.text.isEmpty() && it.text == "hello"
                            value.value = it
                            if (replacesEmptyText) pasted.countDown()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            .use { session ->
                session.frame(1_000_000)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                assertTrue(session.key(KeyInput(UiKey.A, true, Modifiers(control = true))))
                assertEquals(TextRange(0, 5), ComposeThread.call { value.value.selection })
                assertTrue(session.key(KeyInput(UiKey.C, true, Modifiers(control = true))))
                assertEquals("hello", clipboard.readText())
                session.key(KeyInput(UiKey.X, true, Modifiers(control = true)))
                assertEquals("", ComposeThread.call { value.value.text })
                session.key(KeyInput(UiKey.V, true, Modifiers(control = true)))
                // Compose reads clipboard data on Dispatchers.IO after key delivery returns.
                assertTrue(pasted.await(2, TimeUnit.SECONDS), "Paste did not update the text field")
                assertEquals("hello", ComposeThread.call { value.value.text })
            }
    }

    @Test
    fun `closing twice disposes composition once and rejects future work`() {
        val disposed = AtomicInteger()
        val session =
            UiSession(Viewport(64, 64)) {
                DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                Box(Modifier.fillMaxSize().background(Color.Green))
            }
        session.frame(1_000_000)?.close()
        session.close()
        session.close()
        assertEquals(1, disposed.get())
        assertEquals(SessionState.CLOSED, session.state)
        assertFalse(session.post(Runnable {}))
        assertFailsWith<IllegalStateException> { session.frame() }
    }

    @Test
    fun `resize keeps the same composition and emits a new generation`() {
        val compositions = AtomicInteger()
        UiSession(Viewport(64, 64)) {
                DisposableEffect(Unit) {
                    compositions.incrementAndGet()
                    onDispose {}
                }
                Box(Modifier.fillMaxSize().background(Color.Red))
            }
            .use { session ->
                val first = checkNotNull(session.frame(1_000_000))
                session.resize(Viewport(128, 96, 2f))
                val second = checkNotNull(session.frame(2_000_000))
                first.use { old ->
                    second.use { new ->
                        assertTrue(new.generation > old.generation)
                        assertEquals(Viewport(128, 96, 2f), new.viewport)
                        assertEquals(1, compositions.get())
                    }
                }
            }
    }

    @Test
    fun `foreign producer can post but cannot access scene directly`() {
        UiSession(Viewport(64, 64)) {}
            .use { session ->
                val executed = AtomicReference<String>()
                val error = AtomicReference<Throwable>()
                val producer = Thread {
                    session.post(Runnable { executed.set(Thread.currentThread().name) })
                    try {
                        session.frame()
                    } catch (failure: Throwable) {
                        error.set(failure)
                    }
                }
                producer.start()
                producer.join()
                session.frame(1_000_000)?.close()
                assertIs<IllegalStateException>(error.get())
                assertTrue(executed.get().startsWith("AWT-EventQueue"))
            }
    }
}
