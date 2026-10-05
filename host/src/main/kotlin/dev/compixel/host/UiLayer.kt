package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import dev.compixel.bridge.ComposeThread
import dev.compixel.platform.KeyInput
import dev.compixel.platform.Modifiers
import dev.compixel.platform.MouseButton
import dev.compixel.platform.PointerAction
import dev.compixel.platform.PointerInput
import dev.compixel.render.CpuPhase
import dev.compixel.render.FrameRenderer
import dev.compixel.render.RecordedFrame
import dev.compixel.render.RenderBackend
import dev.compixel.render.RendererStatistics
import dev.compixel.render.UiFrameProfiler
import dev.compixel.render.measureCpu
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.UiDesign
import dev.compixel.ui.UiFeedback
import dev.compixel.ui.theme.LocalThemeCatalog
import dev.compixel.ui.theme.ThemeCatalog
import dev.compixel.ui.theme.ThemeId
import java.util.concurrent.atomic.AtomicBoolean

/** Native images that a [UiLayer] prepares before each frame, such as item icons the game draws. */
interface NativeImageSource : AutoCloseable {
    /** The session recorded frame [frameGeneration], whose pictures may request images. */
    fun recorded(frameGeneration: Long)

    /** Prepares the requested images; true when that changed what the frame shows, so the layer records it again. */
    fun prepare(now: Long, current: ScreenMetrics): Boolean

    /** Discards prepared images after the platform reset its GPU resources. */
    fun reset()
}

/** One session's renderer and native images. A [UiLayer] creates them when it opens and closes them in order. */
class UiLayerSurface<D>(
    val renderer: FrameRenderer<D>,
    val items: NativeImageSource,
    val tooltips: NativeImageSource,
    val drawings: NativeImageSource,
)

/**
 * A Compose session drawn inside a game's screen or HUD, with its renderer and native images: everything about such a
 * host that does not depend on the game. A platform subclass supplies the window, clipboard, feedback, renderer and
 * native images and translates the game's input. The layer is not a screen: the host keeps the native lifecycle and
 * input policy, and the input methods report whether Compose consumed an event. Every member runs on the game thread.
 *
 * Each session starts on [open] from a new snapshot of [contentState] and ends on [close]. Its content is wrapped in
 * the host's [design] for [theme], inside [LocalUiFeedback] and [LocalThemeCatalog]. [prepare] records and renders a
 * frame, so the host can read its layout before drawing beneath Compose, and [render] presents it. The input methods
 * forward events to Compose; [handled] then runs what the content asked for during the event.
 *
 * [G] is the platform's graphics context for drawing and [D] its renderer's presentation destination.
 */
abstract class UiLayer<G, D>(
    val renderBackend: RenderBackend,
    private val guiUnitsPerDp: Float,
    private val minimumUiDensity: Float,
    private val theme: ThemeId,
    private val design: UiDesign,
    private val windowFocused: () -> Boolean,
    /** The platform's layout-to-snapshot handoff, on the game thread before presentation. */
    private val prepareFrameContent: () -> Boolean,
    /** The content's game state, opened before each session composes and closed with it. */
    private val contentState: UiStateBinding<*, *>,
    /** Closes the host, as its own close action does, when the content asked for it through [requestClose]. */
    private val closeHost: () -> Unit,
    private val content: @Composable () -> Unit,
) {
    var session: UiSession? = null
        private set

    private var surface: UiLayerSurface<D>? = null
    private val clipboard = ClipboardMailbox()
    private val characters = CommittedCharacters()
    private val closeRequested = AtomicBoolean()
    private val pendingFeedback = AtomicBoolean()
    private val feedback = UiFeedback { pendingFeedback.set(true) }
    // Whether this session already warned that its content sent more actions than the host can queue.
    private var rejectionsReported = false
    private var seenResourceEpoch = 0L
    private var themeCatalog: ThemeCatalog? = null
    private val themeState = ComposeThread.call { mutableStateOf(ThemeCatalog.Empty) }
    private var metrics: ScreenMetrics? = null
    /** Set by [prepare] for the [render] call that presents the frame. */
    private var prepared: ScreenMetrics? = null
    private var windowFocus: Boolean? = null
    private var closedStatistics = RendererStatistics(renderBackend, 0, 0, 0)

    val rendererStatistics: RendererStatistics
        get() = surface?.renderer?.statistics ?: closedStatistics

    /** Whether [prepare] made a frame that [render] has not presented yet. */
    val framePrepared: Boolean
        get() = prepared != null

    val hasTextInputFocus: Boolean
        get() = session?.hasTextInputFocus == true

    /** Opt-in per-frame history. Read snapshots on the game/render thread. */
    val frameProfiler: UiFrameProfiler? =
        if (java.lang.Boolean.getBoolean("compixel.profile"))
            UiFrameProfiler(measureAllocations = java.lang.Boolean.getBoolean("compixel.allocations"))
        else null

    init {
        require(guiUnitsPerDp.isFinite() && guiUnitsPerDp > 0f)
        require(minimumUiDensity.isFinite() && minimumUiDensity >= 0f)
    }

    /** Fails unless the caller runs on the platform's render thread. */
    protected abstract fun assertRenderThread()

    /** The window's framebuffer width in pixels. */
    protected abstract val framebufferWidth: Int

    /** The window's framebuffer height in pixels. */
    protected abstract val framebufferHeight: Int

    /** Framebuffer pixels per GUI unit. */
    protected abstract val guiScale: Float

    /** The system clipboard's text, read and written on the game thread. */
    protected abstract var systemClipboard: String

    /** Gives the native feedback for an activated control, such as the click sound of a vanilla button. */
    protected abstract fun playFeedback()

    /** The modifier keys held now, which pointer events carry. */
    protected abstract fun pointerModifiers(): Modifiers

    /** Changes whenever the platform reset its GPU resources, for example after a resource reload. */
    protected abstract val resourceEpoch: Long

    /** The theme files of the current resources. */
    protected abstract val themes: ThemeCatalog

    /** Reports a problem of the content, for example actions the host refused. */
    protected abstract fun warn(message: String)

    /** Creates a new session's renderer and native images. */
    protected abstract fun createSurface(profiler: UiFrameProfiler?): UiLayerSurface<D>

    /** Where [render] presents the frame. */
    protected abstract fun destination(graphics: G, metrics: ScreenMetrics): D

    /** Provides the platform's composition locals around the content, such as its native image mailboxes. */
    @Composable
    protected open fun ProvideContent(content: @Composable () -> Unit) {
        content()
    }

    /** Hides transient native overlays, such as an item tooltip, as pointer presses, keys and resizes do. */
    protected open fun dismissTransient() {}

    /** Runs before a frame is prepared, for example to draw what the platform batched so far. */
    protected open fun beforePrepare(graphics: G?) {}

    /** Runs right before the renderer presents the frame. */
    protected open fun beforePresent(graphics: G) {}

    /** Runs after each prepared frame, for example to follow the focused text field with the platform's text input. */
    protected open fun afterFrame(session: UiSession, metrics: ScreenMetrics) {}

    /** Runs before a pointer press reaches Compose. */
    protected open fun beforePress(session: UiSession?) {}

    /** Runs first when the layer closes. */
    protected open fun beforeClose() {}

    /** Adds the platform's native resources to an enabled [frameProfiler] after each presented frame. */
    protected open fun profileResources(profiler: UiFrameProfiler) {}

    /** Creates the session and renderer for a [width]x[height] GUI area, or resizes the open session. */
    fun open(width: Int, height: Int) {
        assertRenderThread()
        val current = currentMetrics(width, height)
        refreshClipboard()
        refreshTheme()
        val existing = session
        if (existing == null || existing.state == SessionState.CLOSED) {
            // The first composition already reads the game, not a placeholder.
            contentState.open()
            rejectionsReported = false
            val created = createSurface(frameProfiler)
            try {
                session =
                    UiSession(current.viewport, clipboard) {
                        CompositionLocalProvider(
                            LocalUiFeedback provides feedback,
                            LocalThemeCatalog provides themeState.value,
                        ) {
                            ProvideContent { design.Decorate(theme, content) }
                        }
                    }
                surface = created
                windowFocus = null
            } catch (error: Throwable) {
                closeSurface(created)
                throw error
            }
            seenResourceEpoch = resourceEpoch
        } else existing.resize(current.viewport)
        metrics = current
        updateWindowFocus()
    }

    /** Lays the content out ahead of the first frame, so the host can read layout geometry while it initializes. */
    fun layout() {
        val active = session ?: return
        active.frame()?.close()
        active.invalidate() // The first presented frame records again.
    }

    /**
     * Records and renders the next frame, ahead of the [render] call that presents it, so the host can read this
     * frame's layout before drawing anything beneath Compose. [graphics] is null where the platform prepares frames
     * without a graphics context.
     */
    fun prepare(graphics: G?, width: Int, height: Int) {
        assertRenderThread()
        if (prepared != null) {
            prepared = null
            frameProfiler?.endFrame()
        } // The host never presented it.
        if (session == null) return
        val profiler = frameProfiler
        if (profiler == null) {
            prepared = prepareFrame(graphics, width, height)
            return
        }
        profiler.beginFrame()
        try {
            prepared = ComposeThread.traceCalls(profiler) { prepareFrame(graphics, width, height) }
        } catch (error: Throwable) {
            profiler.endFrame()
            throw error
        }
    }

    /** Presents the frame from [prepare], preparing one first when the host did not. */
    fun render(graphics: G, width: Int, height: Int) {
        if (prepared == null) prepare(graphics, width, height)
        val current = prepared ?: return
        prepared = null
        val profiler = frameProfiler
        if (profiler == null) present(graphics, current)
        else
            try {
                ComposeThread.traceCalls(profiler) { present(graphics, current) }
            } finally {
                profiler.endFrame()
            }
    }

    private fun prepareFrame(graphics: G?, width: Int, height: Int): ScreenMetrics? {
        val active = session ?: return null
        val surface = checkNotNull(surface)
        val backend = surface.renderer
        val current =
            frameProfiler.measureCpu(CpuPhase.HOST) {
                val current = currentMetrics(width, height)
                if (current != metrics) {
                    dismissTransient()
                    active.resize(current.viewport)
                    metrics = current
                }
                updateWindowFocus()
                refreshTheme()
                beforePrepare(graphics)
                val epoch = resourceEpoch
                if (seenResourceEpoch != epoch) {
                    surface.drawings.reset()
                    surface.items.reset()
                    surface.tooltips.reset()
                    backend.reset()
                    seenResourceEpoch = epoch
                }
                if (backend.needsFrame) active.invalidate()
                current
            }
        fun recordFrame(): RecordedFrame? =
            active.frame()?.also {
                frameProfiler?.recorded(it.generation)
                surface.items.recorded(it.generation)
                surface.tooltips.recorded(it.generation)
                surface.drawings.recorded(it.generation)
            }
        var frame = frameProfiler.measureCpu(CpuPhase.RECORD) { recordFrame() }
        fun replaceFrame() {
            frameProfiler.measureCpu(CpuPhase.FRAME_RELEASE) { frame?.close() }
            frame = null
            frame =
                frameProfiler.measureCpu(CpuPhase.RECORD) {
                    active.invalidate()
                    recordFrame()
                }
        }
        try {
            // A container first learns its visible slots during layout. Publish their values
            // before image preparation and presentation, including newly scrolled-in slots.
            if (frameProfiler.measureCpu(CpuPhase.HOST) { prepareFrameContent() }) replaceFrame()
            val itemsChanged =
                frameProfiler.measureCpu(CpuPhase.ITEMS) { surface.items.prepare(System.nanoTime(), current) }
            val tooltipChanged =
                frameProfiler.measureCpu(CpuPhase.TOOLTIP) {
                    surface.tooltips.prepare(System.nanoTime(), current)
                }
            val drawingsChanged =
                frameProfiler.measureCpu(CpuPhase.ITEMS) {
                    surface.drawings.prepare(System.nanoTime(), current)
                }
            if (itemsChanged || tooltipChanged || drawingsChanged) replaceFrame()
        } catch (error: Throwable) {
            frame?.close()
            throw error
        }
        frame?.let {
            try {
                frameProfiler.measureCpu(CpuPhase.RENDER) { backend.render(it) }
                frameProfiler?.rendered()
            } finally {
                frameProfiler.measureCpu(CpuPhase.FRAME_RELEASE) { it.close() }
            }
        }
        frameProfiler.measureCpu(CpuPhase.HOST) {
            flushClipboard()
            afterFrame(active, current)
        }
        return current
    }

    private fun present(graphics: G, current: ScreenMetrics) {
        val backend = surface?.renderer ?: return
        frameProfiler.measureCpu(CpuPhase.PRESENT) {
            beforePresent(graphics)
            backend.present(destination(graphics, current))
        }
        frameProfiler?.let(::profileResources)
    }

    /** Follows the window focus and passes on the clipboard and feedback; hosts call it every game tick. */
    fun tick() {
        updateWindowFocus()
        flushClipboard()
        flushFeedback()
        reportRejectedActions()
    }

    /** Runs the content's pending actions, publishes a snapshot and then closes the host if the content asked to. */
    fun tickContent() {
        contentState.tick()
        if (closeRequested.getAndSet(false)) closeHost()
    }

    /**
     * Closes the host on the game thread: before the input event during which it was called returns, otherwise at the
     * next [tickContent]. Any thread may call it, for example a close button in the content.
     */
    fun requestClose() = closeRequested.set(true)

    /**
     * Runs what the content asked for while Compose handled an input event, before the event returns, as vanilla
     * widgets act inside their input handlers: the actions it sent, then a close request. True when Compose [consumed]
     * the event or the content closed the host, which then takes nothing more from the event.
     */
    fun handled(consumed: Boolean): Boolean {
        contentState.handleActions()
        if (closeRequested.getAndSet(false)) closeHost()
        return consumed || !contentState.isOpen
    }

    fun press(x: Double, y: Double, button: MouseButton?): Boolean {
        refreshClipboard()
        beforePress(session)
        return pointer(PointerAction.PRESS, x, y, button)
    }

    fun release(x: Double, y: Double, button: MouseButton?): Boolean = pointer(PointerAction.RELEASE, x, y, button)

    fun move(x: Double, y: Double): Boolean = pointer(PointerAction.MOVE, x, y)

    fun scroll(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean =
        pointer(PointerAction.SCROLL, x, y, scrollX = -scrollX.toFloat(), scrollY = -scrollY.toFloat())

    fun key(input: KeyInput): Boolean {
        if (input.pressed) {
            refreshClipboard()
            dismissTransient()
        }
        val consumed = session?.key(input) == true
        flushClipboard()
        flushFeedback()
        return consumed
    }

    /**
     * Commits a typed UTF-16 unit. A high surrogate is held for its pair; it counts as consumed while a text input has
     * focus.
     */
    fun typeCharacter(character: Char): Boolean {
        val text = characters.accept(character) ?: return character.isHighSurrogate() && hasTextInputFocus
        return session?.commitText(text) == true
    }

    /** Commits typed text, such as one code point; true when Compose took any of it. */
    fun typeText(text: String): Boolean {
        var consumed = false
        text.forEach { character ->
            characters.accept(character)?.let { consumed = session?.commitText(it) == true || consumed }
        }
        return consumed
    }

    /** Releases the session and every owned resource. [open] can start a new session afterwards. */
    fun close() {
        if (prepared != null) {
            prepared = null
            frameProfiler?.endFrame()
        }
        beforeClose()
        closeRequested.set(false) // A request ends with the session that made it.
        try {
            session?.close()
        } finally {
            session = null
            characters.reset()
            // After the composition is disposed, so the content reads its state until the end.
            contentState.close()
            surface?.let { closing ->
                surface = null
                closeSurface(closing)
            }
        }
    }

    private fun closeSurface(closing: UiLayerSurface<D>) {
        try {
            closing.items.close()
        } finally {
            try {
                closing.tooltips.close()
            } finally {
                try {
                    closing.drawings.close()
                } finally {
                    try {
                        closing.renderer.close()
                    } finally {
                        closedStatistics = closing.renderer.statistics
                    }
                }
            }
        }
    }

    fun currentMetrics(width: Int, height: Int): ScreenMetrics =
        ScreenMetrics(
            framebufferWidth.coerceAtLeast(1),
            framebufferHeight.coerceAtLeast(1),
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            guiScale,
            guiUnitsPerDp,
            minimumUiDensity,
        )

    private fun refreshTheme() {
        val next = themes
        if (themeCatalog === next) return
        themeCatalog = next
        ComposeThread.call { themeState.value = next }
    }

    private fun flushFeedback() {
        if (pendingFeedback.getAndSet(false)) playFeedback()
    }

    // A send() the queue refused loses its action. Say so once per session, without flooding the log.
    private fun reportRejectedActions() {
        if (rejectionsReported) return
        val rejected = contentState.rejectedActions
        if (rejected == 0L) return
        rejectionsReported = true
        warn("UI content sent more actions than its screen can queue; $rejected were rejected")
    }

    private fun updateWindowFocus() {
        val now = windowFocused()
        if (now == windowFocus) return
        characters.reset()
        windowFocus = now
        if (!now) dismissTransient()
        session?.setFocused(now)
    }

    private fun refreshClipboard() {
        flushClipboard()
        clipboard.refresh(systemClipboard)
    }

    private fun flushClipboard() {
        clipboard.takeWrite()?.let { systemClipboard = it }
    }

    private fun pointer(
        action: PointerAction,
        x: Double,
        y: Double,
        button: MouseButton? = null,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
    ): Boolean {
        val current = metrics ?: return false
        if (action == PointerAction.PRESS || action == PointerAction.SCROLL || action == PointerAction.EXIT) {
            dismissTransient()
        }
        val consumed =
            session?.pointer(
                PointerInput(action, current.pixelX(x), current.pixelY(y), button, scrollX, scrollY, pointerModifiers())
            ) ?: false
        flushFeedback()
        return consumed
    }
}
