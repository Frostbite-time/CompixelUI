package dev.compixel.forge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.input.ClipboardMailbox
import dev.compixel.forge.input.CommittedCharacters
import dev.compixel.forge.input.MinecraftTextInput
import dev.compixel.forge.input.toModifiers
import dev.compixel.forge.input.toMouseButton
import dev.compixel.forge.input.toUiKey
import dev.compixel.forge.item.ItemImageMailbox
import dev.compixel.forge.item.ItemTooltipMailbox
import dev.compixel.forge.item.LocalItemImages
import dev.compixel.forge.item.LocalItemTooltips
import dev.compixel.forge.item.NativeItemAtlas
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.item.NativeItemStatistics
import dev.compixel.forge.item.NativeTooltipRenderer
import dev.compixel.forge.item.NativeTooltipStatistics
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.render.ScreenFrameRenderer
import dev.compixel.forge.render.ScreenMetrics
import dev.compixel.forge.render.ScreenRenderDestination
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.forge.render.createScreenRenderer
import dev.compixel.forge.theme.OreThemeReloadListener
import dev.compixel.host.SessionState
import dev.compixel.host.UiSession
import dev.compixel.platform.*
import dev.compixel.render.*
import dev.compixel.ui.ore.theme.OreFeedback
import dev.compixel.ui.ore.theme.OreTheme
import dev.compixel.ui.ore.theme.OreThemeId
import dev.compixel.ui.ore.theme.OreThemeResources
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent // IME
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * A Compose session with its renderer, native items and tooltips, drawn inside a host screen. It is not a Screen: the
 * host keeps the native lifecycle, screen events and input policy, and the input methods report only whether Compose
 * consumed an event. Every member runs on the game thread.
 */
internal class ComposeLayer(
    val renderBackend: RenderBackend = configuredRenderBackend(),
    private val guiUnitsPerDp: Float = 1f,
    private val nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    private val minimumUiDensity: Float = 1f,
    private val theme: OreThemeId = OreThemeId.Default,
    private val windowFocused: () -> Boolean,
    /** Adapter-owned layout-to-snapshot handoff, on the game thread before presentation. */
    private val prepareFrameContent: () -> Boolean = { false },
    private val content: @Composable () -> Unit,
) {
    var session: UiSession? = null
        private set

    private val clipboard = ClipboardMailbox()
    private val characters = CommittedCharacters()
    private val pendingOreFeedback = AtomicBoolean()
    private val oreFeedback = OreFeedback { pendingOreFeedback.set(true) }
    private var renderer: ScreenFrameRenderer? = null
    private val itemMailbox = ComposeThread.call { ItemImageMailbox(nativeItemOptions.cacheCapacity * 4) }
    private val tooltipMailbox = ComposeThread.call { ItemTooltipMailbox() }
    private var nativeItems: NativeItemAtlas? = null
    private var nativeTooltips: NativeTooltipRenderer? = null
    private var closedItemStatistics = NativeItemStatistics()
    private var closedTooltipStatistics = NativeTooltipStatistics()
    val nativeItemStatistics: NativeItemStatistics
        get() = nativeItems?.statistics ?: closedItemStatistics

    val nativeTooltipStatistics: NativeTooltipStatistics
        get() = nativeTooltips?.statistics ?: closedTooltipStatistics

    val nativeTooltipBounds
        get() = ComposeThread.call { tooltipMailbox.bounds }

    private var resourceEpoch = RendererResources.epoch
    private var themeCatalog = OreThemeReloadListener.catalog
    private val themeState = ComposeThread.call { mutableStateOf(themeCatalog) }

    private fun refreshTheme() {
        val next = OreThemeReloadListener.catalog
        if (themeCatalog === next) return
        themeCatalog = next
        ComposeThread.call { themeState.value = next }
    }

    private var metrics: ScreenMetrics? = null
    /** Set by [prepare] for the [render] call that presents the frame. */
    private var prepared: ScreenMetrics? = null
    val framePrepared: Boolean
        get() = prepared != null

    private var windowFocus: Boolean? = null
    private var closedStatistics = RendererStatistics(renderBackend, 0, 0, 0)
    val rendererStatistics: RendererStatistics
        get() = renderer?.statistics ?: closedStatistics

    val hasTextInputFocus: Boolean
        get() = session?.hasTextInputFocus == true

    // IME support (26.x only). Minecraft's text input and input method composition are handled in
    // MinecraftTextInput.kt; 1.20.1 and 1.21.1 have neither, so their layers lack every line marked
    // "IME". Keep those lines when comparing or syncing this file with the other adapters.
    private val textInput = MinecraftTextInput() // IME
    /** Whether Compose holds Minecraft's text input for a focused text field. */
    val textInputOpen: Boolean
        get() = textInput.open // IME

    /** Opt-in per-frame history. Read snapshots on the game/render thread. */
    val frameProfiler: UiFrameProfiler? =
        if (java.lang.Boolean.getBoolean("compixel.profile"))
            UiFrameProfiler(measureAllocations = java.lang.Boolean.getBoolean("compixel.allocations"))
        else null

    init {
        require(guiUnitsPerDp.isFinite() && guiUnitsPerDp > 0f)
        require(minimumUiDensity.isFinite() && minimumUiDensity >= 0f)
    }

    /** Creates the session and renderer for a [width]x[height] GUI area, or resizes the open session. */
    fun open(width: Int, height: Int) {
        RenderSystem.assertOnRenderThread()
        val current = currentMetrics(width, height)
        refreshClipboard()
        refreshTheme()
        val existing = session
        if (existing == null || existing.state == SessionState.CLOSED) {
            val backend = createScreenRenderer(renderBackend, frameProfiler)
            try {
                session =
                    UiSession(current.viewport, clipboard) {
                        CompositionLocalProvider(
                            LocalItemImages provides itemMailbox,
                            LocalItemTooltips provides tooltipMailbox,
                        ) {
                            OreThemeResources(themeState.value) {
                                OreTheme(id = theme, feedback = oreFeedback, content = content)
                            }
                        }
                    }
                renderer = backend
                nativeItems = NativeItemAtlas(itemMailbox, nativeItemOptions, backend.nativeSnapshots)
                nativeTooltips = NativeTooltipRenderer(tooltipMailbox, backend.nativeSnapshots)
                windowFocus = null
            } catch (error: Throwable) {
                backend.close()
                throw error
            }
            resourceEpoch = RendererResources.epoch
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
     * frame's layout before anything beneath Compose is extracted.
     */
    fun prepare(width: Int, height: Int) {
        RenderSystem.assertOnRenderThread()
        if (prepared != null) {
            prepared = null
            frameProfiler?.endFrame()
        } // The host never presented it.
        if (session == null) return
        val profiler = frameProfiler
        if (profiler == null) {
            prepared = prepareFrame(width, height)
            return
        }
        profiler.beginFrame()
        try {
            prepared = ComposeThread.traceCalls(profiler) { prepareFrame(width, height) }
        } catch (error: Throwable) {
            profiler.endFrame()
            throw error
        }
    }

    /** Presents the frame from [prepare], preparing one first when the host did not. */
    fun render(guiGraphics: GuiGraphicsExtractor, width: Int, height: Int) {
        if (prepared == null) prepare(width, height)
        val current = prepared ?: return
        prepared = null
        val profiler = frameProfiler
        if (profiler == null) present(guiGraphics, current)
        else
            try {
                ComposeThread.traceCalls(profiler) { present(guiGraphics, current) }
            } finally {
                profiler.endFrame()
            }
    }

    private fun prepareFrame(width: Int, height: Int): ScreenMetrics? {
        val active = session ?: return null
        val backend = checkNotNull(renderer)
        val items = checkNotNull(nativeItems)
        val tooltips = checkNotNull(nativeTooltips)
        val current =
            frameProfiler.measureCpu(CpuPhase.HOST) {
                val current = currentMetrics(width, height)
                if (current != metrics) {
                    ComposeThread.call { tooltipMailbox.dismiss() }
                    active.resize(current.viewport)
                    metrics = current
                }
                updateWindowFocus()
                refreshTheme()
                val currentResourceEpoch = RendererResources.epoch
                if (resourceEpoch != currentResourceEpoch) {
                    items.reset()
                    tooltips.reset()
                    backend.reset()
                    resourceEpoch = currentResourceEpoch
                }
                if (backend.needsFrame) active.invalidate()
                current
            }
        fun recordFrame(): RecordedFrame? =
            active.frame()?.also {
                frameProfiler?.recorded(it.generation)
                items.recorded(it.generation)
                tooltips.recorded(it.generation)
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
            val itemsChanged = frameProfiler.measureCpu(CpuPhase.ITEMS) { items.prepare(System.nanoTime()) }
            val tooltipChanged =
                frameProfiler.measureCpu(CpuPhase.TOOLTIP) { tooltips.prepare(System.nanoTime(), current) }
            if (itemsChanged || tooltipChanged) replaceFrame()
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
            textInput.afterFrame(active, current)
        } // IME
        return current
    }

    private fun present(guiGraphics: GuiGraphicsExtractor, current: ScreenMetrics) {
        val backend = renderer ?: return
        frameProfiler.measureCpu(CpuPhase.PRESENT) { backend.present(ScreenRenderDestination(guiGraphics, current)) }
        frameProfiler?.let { profiler ->
            val itemStats = nativeItems?.statistics ?: return@let
            profiler.resources(
                itemStats.activeVariants,
                itemStats.cachedImages,
                itemStats.pendingImages,
                nativeTooltips?.statistics?.visible ?: false,
            )
        }
    }

    fun tick() {
        updateWindowFocus()
        flushClipboard()
        flushOreFeedback()
    }

    fun press(x: Double, y: Double, button: Int): Boolean {
        refreshClipboard()
        textInput.beforePress(session) // IME: a press discards an unconfirmed composition.
        return pointer(PointerAction.PRESS, x, y, button.toMouseButton())
    }

    fun release(x: Double, y: Double, button: Int): Boolean =
        pointer(PointerAction.RELEASE, x, y, button.toMouseButton())

    fun move(x: Double, y: Double): Boolean = pointer(PointerAction.MOVE, x, y)

    fun scroll(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean =
        pointer(PointerAction.SCROLL, x, y, scrollX = -scrollX.toFloat(), scrollY = -scrollY.toFloat())

    fun keyPressed(event: KeyEvent): Boolean {
        refreshClipboard()
        ComposeThread.call { tooltipMailbox.dismiss() }
        val consumed = session?.key(KeyInput(event.toUiKey(), true, event.modifiers().toModifiers())) == true
        flushClipboard()
        flushOreFeedback()
        return consumed
    }

    fun keyReleased(event: KeyEvent): Boolean {
        val consumed = session?.key(KeyInput(event.toUiKey(), false, event.modifiers().toModifiers())) == true
        flushClipboard()
        flushOreFeedback()
        return consumed
    }

    fun charTyped(event: CharacterEvent): Boolean {
        var consumed = false
        event.codepointAsString().forEach { character ->
            characters.accept(character)?.let { consumed = session?.commitText(it) == true || consumed }
        }
        return consumed
    }

    /** IME: called by the host after its native focus changes; see [MinecraftTextInput]. */
    fun nativeFocusChanged(focused: Boolean) = textInput.nativeFocusChanged(session, focused) // IME

    /** IME: the input method's composition for the focused text field; see [MinecraftTextInput]. */
    fun preedit(event: PreeditEvent?): Boolean = textInput.preedit(session, event) // IME

    /** Releases the session and every owned resource. [open] can start a new session afterwards. */
    fun close() {
        if (prepared != null) {
            prepared = null
            frameProfiler?.endFrame()
        }
        textInput.close() // IME
        try {
            session?.close()
        } finally {
            session = null
            characters.reset()
            try {
                nativeItems?.let { items ->
                    try {
                        items.close()
                    } finally {
                        closedItemStatistics = items.statistics
                    }
                }
            } finally {
                nativeItems = null
                try {
                    nativeTooltips?.let { tooltips ->
                        try {
                            tooltips.close()
                        } finally {
                            closedTooltipStatistics = tooltips.statistics
                        }
                    }
                } finally {
                    nativeTooltips = null
                    try {
                        renderer?.let { backend ->
                            try {
                                backend.close()
                            } finally {
                                closedStatistics = backend.statistics
                            }
                        }
                    } finally {
                        renderer = null
                    }
                }
            }
        }
    }

    private fun flushOreFeedback() {
        if (pendingOreFeedback.getAndSet(false)) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
        }
    }

    private fun updateWindowFocus() {
        val now = windowFocused()
        if (now == windowFocus) return
        characters.reset()
        windowFocus = now
        if (!now) ComposeThread.call { tooltipMailbox.dismiss() }
        session?.setFocused(now)
    }

    private fun refreshClipboard() {
        flushClipboard()
        clipboard.refresh(Minecraft.getInstance().keyboardHandler.clipboard)
    }

    private fun flushClipboard() {
        clipboard.takeWrite()?.let { Minecraft.getInstance().keyboardHandler.clipboard = it }
    }

    private fun currentMetrics(width: Int, height: Int): ScreenMetrics {
        val window = Minecraft.getInstance().window
        return ScreenMetrics(
            window.width.coerceAtLeast(1),
            window.height.coerceAtLeast(1),
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            window.guiScale.toFloat(),
            guiUnitsPerDp,
            minimumUiDensity,
        )
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
            ComposeThread.call { tooltipMailbox.dismiss() }
        }
        val consumed =
            session?.pointer(
                PointerInput(
                    action,
                    current.pixelX(x),
                    current.pixelY(y),
                    button,
                    scrollX,
                    scrollY,
                    Modifiers(
                        Minecraft.getInstance().hasShiftDown(),
                        Minecraft.getInstance().hasControlDown(),
                        Minecraft.getInstance().hasAltDown(),
                    ),
                )
            ) ?: false
        flushOreFeedback()
        return consumed
    }
}
