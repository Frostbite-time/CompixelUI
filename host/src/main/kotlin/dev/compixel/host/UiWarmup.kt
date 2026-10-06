package dev.compixel.host

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.compixel.platform.PointerAction
import dev.compixel.platform.PointerInput
import dev.compixel.platform.Viewport
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.UiDesign
import dev.compixel.ui.UiFeedback
import dev.compixel.ui.theme.ThemeId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepares Compose before a game's first screen. Otherwise the first session loads and initializes thousands of Compose
 * and Skia classes, its design's fonts and code that runs for the first time, while its host waits on the game thread.
 * [start] does that work once, in a throwaway session on a background thread: it composes typical content inside the
 * design and a [ScreenTransition], moves the pointer across it while recording frames, and closes the session. Nothing
 * is shown or rendered on the GPU.
 *
 * Start it while the game loads, for example at client setup. `-Dcompixel.warmup=false` turns it off.
 */
object UiWarmup {
    private const val FRAMES = 30
    private val started = AtomicBoolean()

    /**
     * Starts the warm-up unless it already started. [content] is what screens usually show, such as the design's common
     * controls; [failed] receives an error on the warm-up thread.
     */
    fun start(design: UiDesign, content: @Composable () -> Unit, failed: (Throwable) -> Unit = {}) {
        if (!started.compareAndSet(false, true) || System.getProperty("compixel.warmup") == "false") return
        val thread =
            Thread(
                {
                    try {
                        warmUp(design, content)
                    } catch (error: Throwable) {
                        failed(error)
                    }
                },
                "CompixelUI warm-up",
            )
        thread.isDaemon = true
        thread.start()
    }

    /** Runs the warm-up session on the calling thread. */
    internal fun warmUp(design: UiDesign, content: @Composable () -> Unit, frames: Int = FRAMES) {
        // A common window: 854 × 480 pixels at a GUI scale of 2
        val viewport = Viewport(854, 480, 2f)
        val presence = ScreenPresence()
        UiSession(viewport) {
                CompositionLocalProvider(
                    LocalUiFeedback provides UiFeedback {},
                    LocalScreenPresence provides presence,
                ) {
                    // Screens commonly slide in as they fade.
                    design.Decorate(ThemeId.Default) {
                        ScreenTransition(
                            enter = fadeIn() + slideInVertically { it / 4 },
                            exit = fadeOut() + slideOutVertically { it / 4 },
                            content = { content() },
                        )
                    }
                }
            }
            .use { session ->
                session.setFocused(true)
                repeat(frames) { frame ->
                    // The pointer crosses the content diagonally, so hover, hit testing and tooltips run too.
                    val progress = frame.toFloat() / frames
                    session.pointer(
                        PointerInput(PointerAction.MOVE, viewport.width * progress, viewport.height * progress)
                    )
                    session.frame()?.close()
                }
            }
    }
}
