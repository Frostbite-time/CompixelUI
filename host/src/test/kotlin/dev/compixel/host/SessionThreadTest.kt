package dev.compixel.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.compixel.platform.MouseButton
import dev.compixel.platform.PointerAction
import dev.compixel.platform.PointerInput
import dev.compixel.platform.Viewport
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/**
 * Compose 1.12 runs a root's delayed layout callbacks on the AWT event thread unless the scene replaces them: this
 * fails when that replacement misses, for the scene's root or for a popup's.
 */
class SessionThreadTest {
    @Test
    fun `layout callbacks, popups, delays and animations stay on the session thread`() {
        val owner = Thread.currentThread()
        val threads = ConcurrentHashMap.newKeySet<String>()
        fun seen() {
            if (Thread.currentThread() !== owner) threads += Thread.currentThread().name
        }
        val offset = mutableIntStateOf(0)
        val text = mutableStateOf("")
        var rootRects = 0
        var popupRects = 0
        var delays = 0
        var frames = 0
        UiSession(Viewport(320, 240)) {
                Box(
                    Modifier.offset { IntOffset(offset.intValue, 0) }
                        .size(40.dp)
                        // Debounced: RectManager has to schedule a delayed callback to deliver the last rectangle.
                        .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 40) {
                            seen()
                            rootRects++
                        }
                )
                BasicTextField(text.value, { text.value = it }, Modifier.offset(y = 60.dp).size(200.dp, 30.dp))
                Popup {
                    Box(
                        Modifier.offset { IntOffset(0, offset.intValue) }
                            .size(20.dp)
                            .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 40) {
                                seen()
                                popupRects++
                            }
                    )
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(20)
                        seen()
                        delays++
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) withFrameNanos {
                        seen()
                        frames++
                    }
                }
            }
            .use { session ->
                var time = System.nanoTime()
                // Focus the field: its cursor blinks on a delay.
                session.frame(time)?.close()
                session.pointer(PointerInput(PointerAction.PRESS, 20f, 70f, MouseButton.LEFT))
                session.pointer(PointerInput(PointerAction.RELEASE, 20f, 70f, MouseButton.LEFT))
                repeat(120) { step ->
                    // Frames slower than RectManager's 16 ms let its delayed callbacks come due between them.
                    Thread.sleep(if (step % 10 == 0) 60 else 5)
                    if (step % 3 == 0) offset.intValue = step
                    time = System.nanoTime()
                    session.frame(time)?.close()
                }
                assertTrue(session.textInputFocused, "The text field did not take focus")
            }
        assertTrue(rootRects > 1, "The root's rectangle callbacks did not run: $rootRects")
        assertTrue(popupRects > 1, "The popup layer's rectangle callbacks did not run: $popupRects")
        assertTrue(delays > 5, "Delays did not resume: $delays")
        assertTrue(frames > 50, "Frame callbacks did not run: $frames")
        assertEquals(emptySet(), threads.toSet(), "Session code ran on another thread")
    }
}
