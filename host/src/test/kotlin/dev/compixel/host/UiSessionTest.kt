package dev.compixel.host

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.compixel.platform.*
import dev.compixel.platform.Viewport
import dev.compixel.render.RecordedFrame
import dev.compixel.ui.CtrlOrCmd
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
    fun aStateReadWhileDrawingRecordsOneFrame() {
        val color = mutableStateOf(Color.Red)
        UiSession(Viewport(32, 32)) { Box(Modifier.fillMaxSize().drawBehind { drawRect(color.value) }) }
            .use { session ->
                session.frame(1_000_000)?.close()
                session.frame(2_000_000)?.close()
                assertNull(session.frame(3_000_000), "An unchanged scene recorded again")
                color.value = Color.Blue
                checkNotNull(session.frame(4_000_000)) { "The changed state recorded no frame" }
                    .use { frame ->
                        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frame.encodePng()))
                        assertEquals(0xff0000ff.toInt(), image.getRGB(16, 16), "The frame drew the previous state")
                    }
                // The draw invalidation that frame applied must not request another one.
                assertNull(session.frame(5_000_000), "One state change recorded a second frame")
            }
    }

    @Test
    fun aSettledSceneDoesNoWorkUntilStateChanges() {
        val color = mutableStateOf(Color.Red)
        UiSession(Viewport(32, 32)) { Box(Modifier.fillMaxSize().drawBehind { drawRect(color.value) }) }
            .use { session ->
                var time = 1_000_000L
                // The first frames may still finish work they scheduled; after that nothing is due.
                repeat(3) {
                    session.frame(time)?.close()
                    time += 1_000_000
                }
                repeat(3) {
                    assertFalse(session.update(time), "A settled scene composed again")
                    assertNull(session.record(), "A settled scene recorded again")
                    time += 1_000_000
                }
                color.value = Color.Blue
                assertTrue(session.update(time), "The changed state left the scene as it was")
                checkNotNull(session.record()) { "The changed state recorded no frame" }
                    .use { recorded ->
                        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(recorded.encodePng()))
                        assertEquals(0xff0000ff.toInt(), image.getRGB(16, 16), "The frame drew the previous state")
                    }
            }
    }

    @Test
    fun stateWrittenFromTheLayoutShowsInTheSameFrame() {
        var laidOut = 0
        val shown = mutableStateOf(Color.Red)
        UiSession(Viewport(32, 32)) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { laidOut++ }.drawBehind { drawRect(shown.value) })
            }
            .use { session ->
                session.update(1_000_000)
                assertTrue(laidOut > 0, "The layout was not readable before the frame was drawn")
                // A host reads the layout and publishes what it asks for, as a container does for its slots.
                shown.value = Color.Blue
                session.update(1_000_000)
                checkNotNull(session.record()).use { frame ->
                    val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frame.encodePng()))
                    assertEquals(0xff0000ff.toInt(), image.getRGB(16, 16), "The frame drew the state before the layout")
                }
                assertNull(session.frame(2_000_000), "One frame's state recorded a second frame")
            }
    }

    @Test
    fun aStateTheContentWritesLaterRecordsTheNextFrame() {
        val written = CountDownLatch(1)
        UiSession(Viewport(32, 32)) {
                var color by remember { mutableStateOf(Color.Red) }
                LaunchedEffect(Unit) {
                    delay(500)
                    color = Color.Blue
                    written.countDown()
                }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(color) })
            }
            .use { session ->
                var time = 1_000_000L
                repeat(3) { session.frame(time++)?.close() }
                // The delay resumes as a task of the session's thread, which its frames run.
                val deadline = System.nanoTime() + 5_000_000_000
                var changed: RecordedFrame? = null
                while (changed == null && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                    changed = session.frame(time++)
                }
                assertEquals(0, written.count, "The content's effect never ran")
                checkNotNull(changed) { "A state the content wrote while settled recorded no frame" }
                    .use { frame ->
                        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frame.encodePng()))
                        assertEquals(0xff0000ff.toInt(), image.getRGB(16, 16), "The frame drew the previous state")
                    }
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
    fun closeCancelsFrameAwaitersAndDelayedEffectsOnTheSessionThread() {
        val started = CountDownLatch(2)
        val cancelled = CountDownLatch(2)
        val owner = Thread.currentThread()
        val wrongThread = AtomicReference<String?>()
        fun effectCancelled() {
            if (Thread.currentThread() !== owner) wrongThread.set(Thread.currentThread().name)
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
        val value = mutableStateOf(TextFieldValue(""))
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
                assertEquals("active", value.value.text)
            }
    }

    @Test
    fun `frames and window focus refresh the last text input focus`() {
        val value = mutableStateOf(TextFieldValue(""))
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun click() {
                    session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                    session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                }
                session.frame(1_000_000)?.close()
                assertFalse(session.textInputFocused)
                click()
                session.frame(2_000_000)?.close()
                assertTrue(session.textInputFocused)
                session.setFocused(false)
                assertFalse(session.textInputFocused)
                session.setFocused(true)
                click()
                session.frame(3_000_000)?.close()
                assertTrue(session.textInputFocused)
                session.close()
                assertFalse(session.textInputFocused)
            }
    }

    @Test
    fun `composing text shows in the field until committed or removed`() {
        val value = mutableStateOf("")
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun text() = value.value
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
        val value = mutableStateOf(TextFieldValue(""))
        UiSession(Viewport(320, 120)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun current() = value.value
                session.frame(1_000_000)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 10f, 10f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                assertTrue(session.commitText("keep"))
                value.value = value.value.copy(selection = TextRange(0, 4))
                session.frame(3_000_000)?.close()
                assertFalse(session.setComposingText(null))
                assertEquals("keep", current().text)
                assertTrue(session.setComposingText(ComposingText("xyz", cursor = 1)))
                assertEquals(TextFieldValue("xyz", TextRange(1), TextRange(0, 3)), current())
            }
    }

    @Test
    fun `state-based text fields take composing text too`() {
        val state = TextFieldState()
        UiSession(Viewport(320, 120)) {
                BasicTextField(state, modifier = Modifier.fillMaxSize())
            }
            .use { session ->
                fun text() = state.text.toString()
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
                assertNotNull(session.textInputArea)
            }
    }

    @Test
    fun `the text input area follows the caret of the focused field`() {
        val value = mutableStateOf("")
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
                assertNull(session.textInputArea)
                session.pointer(PointerInput(PointerAction.PRESS, 50f, 40f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 50f, 40f, MouseButton.LEFT))
                session.frame(2_000_000)?.close()
                val empty = assertNotNull(session.textInputArea)
                assertTrue(
                    empty.left >= 40f && empty.top >= 30f && empty.bottom <= 70f,
                    "Caret $empty lies outside the field",
                )
                assertTrue(session.commitText("abc"))
                session.frame(3_000_000)?.close()
                assertTrue(
                    assertNotNull(session.textInputArea).left > empty.left,
                    "The area did not follow the caret",
                )
                session.setFocused(false)
                assertNull(session.textInputArea)
            }
    }

    @Test
    fun `Escape dismisses Compose dialog before returning to the host`() {
        val visible = mutableStateOf(true)
        UiSession(Viewport(400, 300)) {
                if (visible.value)
                    Dialog(onDismissRequest = { visible.value = false }) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
            }
            .use { session ->
                session.frame(1_000_000)?.close()
                assertTrue(session.key(KeyInput(UiKey.ESCAPE, true)))
                assertFalse(visible.value)
                session.frame(2_000_000)?.close()
                assertFalse(session.key(KeyInput(UiKey.ESCAPE, true)))
            }
    }

    @Test
    fun `consecutive select copy cut paste uses current text and selection`() {
        // Compose's text shortcuts use CtrlOrCmd: Command on macOS, Control elsewhere.
        val shortcut = if (CtrlOrCmd.isCmd) Modifiers(meta = true) else Modifiers(control = true)
        val value = mutableStateOf(TextFieldValue("hello"))
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
                assertTrue(session.key(KeyInput(UiKey.A, true, shortcut)))
                assertEquals(TextRange(0, 5), value.value.selection)
                assertTrue(session.key(KeyInput(UiKey.C, true, shortcut)))
                assertEquals("hello", clipboard.readText())
                session.key(KeyInput(UiKey.X, true, shortcut))
                assertEquals("", value.value.text)
                session.key(KeyInput(UiKey.V, true, shortcut))
                // Compose reads clipboard data on Dispatchers.IO after key delivery returns.
                assertTrue(pasted.await(2, TimeUnit.SECONDS), "Paste did not update the text field")
                assertEquals("hello", value.value.text)
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
    fun `content that reaches back into its own session fails and leaves it open`() {
        var session: UiSession? = null
        var posted = false
        var refused: Throwable? = null
        val created =
            UiSession(Viewport(64, 64)) {
                Box(
                    Modifier.fillMaxSize()
                        .onGloballyPositioned {}
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                    // Queued work is how content reaches its host; closing from here is not.
                                    posted = checkNotNull(session).post(Runnable {})
                                    refused = runCatching { checkNotNull(session).close() }.exceptionOrNull()
                                }
                            }
                        }
                )
            }
        session = created
        created.frame(1_000_000)?.close()
        created.pointer(PointerInput(PointerAction.PRESS, 10f, 10f, MouseButton.LEFT))
        assertIs<IllegalStateException>(refused)
        assertTrue(posted)
        assertEquals(SessionState.ACTIVE, created.state)
        created.frame(2_000_000)?.close()
        created.close()
        assertEquals(SessionState.CLOSED, created.state)
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
                assertEquals(Thread.currentThread().name, executed.get())
            }
    }
}
