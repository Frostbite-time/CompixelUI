package dev.composemc.forge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.composemc.bridge.ComposeThread
import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.host.SessionState
import dev.composemc.host.UiSession
import dev.composemc.platform.*
import dev.composemc.render.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import dev.composemc.ui.ore.theme.OreTheme
import dev.composemc.ui.ore.theme.OreFeedback
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/** Full-screen Compose adapter. All rendering and resource retirement run on the game thread. */
open class ForgeComposeScreen(
    title: Component,
    private val parent: Screen? = null,
    private val guiUnitsPerDp: Float = 1f,
    val renderBackend: RenderBackend = configuredRenderBackend(),
    private val nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    private val minimumUiDensity: Float = 1f,
    private val content: @Composable () -> Unit,
) : Screen(title) {
    internal var session: UiSession? = null
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
    val nativeItemStatistics: NativeItemStatistics get() = nativeItems?.statistics ?: closedItemStatistics
    val nativeTooltipStatistics: NativeTooltipStatistics get() = nativeTooltips?.statistics ?: closedTooltipStatistics
    internal val nativeTooltipBounds get() = ComposeThread.call { tooltipMailbox.bounds }
    private var resourceEpoch = RendererResources.epoch
    private var metrics: ScreenMetrics? = null
    private var focused: Boolean? = null
    private var closedStatistics = RendererStatistics(renderBackend, 0, 0, 0)
    val rendererStatistics: RendererStatistics get() = renderer?.statistics ?: closedStatistics
    val hasTextInputFocus: Boolean get() = session?.hasTextInputFocus == true
    internal val recordingTimings = FrameTimings()
    internal val renderingTimings = FrameTimings()
    internal val presentationTimings = FrameTimings()
    /** Opt-in per-frame history. Read snapshots on the game/render thread. */
    val frameProfiler: UiFrameProfiler? = if (java.lang.Boolean.getBoolean("composemc.profile"))
        UiFrameProfiler(measureAllocations = java.lang.Boolean.getBoolean("composemc.allocations")) else null

    init {
        require(guiUnitsPerDp.isFinite() && guiUnitsPerDp > 0f)
        require(minimumUiDensity.isFinite() && minimumUiDensity >= 0f)
    }

    override fun init() {
        RenderSystem.assertOnRenderThread()
        val current = currentMetrics()
        refreshClipboard()
        val existing = session
        if (existing == null || existing.state == SessionState.CLOSED) {
            val backend = createScreenRenderer(renderBackend, frameProfiler)
            try {
                session = UiSession(current.viewport, clipboard) {
                    CompositionLocalProvider(LocalItemImages provides itemMailbox, LocalItemTooltips provides tooltipMailbox) {
                        OreTheme(feedback = oreFeedback, content = content)
                    }
                }
                renderer = backend
                nativeItems = NativeItemAtlas(backend, itemMailbox, nativeItemOptions)
                nativeTooltips = NativeTooltipRenderer(backend, tooltipMailbox)
                focused = null
            } catch (error: Throwable) { backend.close(); throw error }
            resourceEpoch = RendererResources.epoch
        } else existing.resize(current.viewport)
        metrics = current
        updateWindowFocus()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        RenderSystem.assertOnRenderThread()
        if (session == null) return
        val profiler = frameProfiler
        if (profiler == null) renderFrame(guiGraphics)
        else {
            profiler.beginFrame()
            try { ComposeThread.traceCalls(profiler) { renderFrame(guiGraphics) } }
            finally { profiler.endFrame() }
        }
    }

    private fun renderFrame(guiGraphics: GuiGraphics) {
        val active = session ?: return
        val backend = checkNotNull(renderer)
        val items = checkNotNull(nativeItems)
        val tooltips = checkNotNull(nativeTooltips)
        val current = frameProfiler.measureCpu(CpuPhase.HOST) {
            val current = currentMetrics()
            if (current != metrics) {
                ComposeThread.call { tooltipMailbox.dismiss() }
                active.resize(current.viewport)
                metrics = current
            }
            updateWindowFocus()
            guiGraphics.flush()
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
        fun recordFrame(): RecordedFrame? = active.frame()?.also {
            frameProfiler?.recorded(it.generation)
            items.recorded(it.generation)
            tooltips.recorded(it.generation)
        }
        var recordStart = System.nanoTime()
        var frame = frameProfiler.measureCpu(CpuPhase.RECORD) { recordFrame() }
        var recordNanos = System.nanoTime() - recordStart
        fun replaceFrame() {
            frameProfiler.measureCpu(CpuPhase.FRAME_RELEASE) { frame?.close() }
            frame = null
            recordStart = System.nanoTime()
            frame = frameProfiler.measureCpu(CpuPhase.RECORD) { active.invalidate(); recordFrame() }
            recordNanos += System.nanoTime() - recordStart
        }
        try {
            // A container first learns its visible slots during layout. Publish their values
            // before image preparation and presentation, including newly scrolled-in slots.
            if (frameProfiler.measureCpu(CpuPhase.HOST) { prepareFrameContent() }) replaceFrame()
            val itemsChanged = frameProfiler.measureCpu(CpuPhase.ITEMS) { items.prepare(System.nanoTime()) }
            val tooltipChanged = frameProfiler.measureCpu(CpuPhase.TOOLTIP) { tooltips.prepare(System.nanoTime(), current) }
            if (itemsChanged || tooltipChanged) replaceFrame()
        } catch (error: Throwable) { frame?.close(); throw error }
        recordingTimings.record(recordNanos)
        frame?.let {
            try {
                val renderStart = System.nanoTime()
                frameProfiler.measureCpu(CpuPhase.RENDER) { backend.render(it) }
                frameProfiler?.rendered()
                renderingTimings.record(System.nanoTime() - renderStart)
            } finally { frameProfiler.measureCpu(CpuPhase.FRAME_RELEASE) { it.close() } }
        }
        frameProfiler.measureCpu(CpuPhase.HOST) { flushClipboard() }
        val presentStart = System.nanoTime()
        frameProfiler.measureCpu(CpuPhase.PRESENT) { backend.present(ScreenRenderDestination(guiGraphics, current)) }
        presentationTimings.record(System.nanoTime() - presentStart)
        frameProfiler?.let { profiler ->
            val itemStats = items.statistics
            profiler.resources(itemStats.activeVariants, itemStats.cachedImages, itemStats.pendingImages, tooltips.statistics.visible)
        }
    }

    override fun tick() { updateWindowFocus(); flushClipboard(); flushOreFeedback() }

    /** Adapter-owned layout-to-snapshot handoff, on the game thread before presentation. */
    internal open fun prepareFrameContent(): Boolean = false

    private fun flushOreFeedback() {
        if (pendingOreFeedback.getAndSet(false)) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        refreshClipboard()
        return pointer(PointerAction.PRESS, mouseX, mouseY, button.toMouseButton())
    }
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean =
        pointer(PointerAction.RELEASE, mouseX, mouseY, button.toMouseButton())
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        pointer(PointerAction.MOVE, mouseX, mouseY)
    override fun mouseMoved(mouseX: Double, mouseY: Double) { pointer(PointerAction.MOVE, mouseX, mouseY) }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        pointer(PointerAction.SCROLL, mouseX, mouseY, scrollX = 0f, scrollY = -scrollY.toFloat())

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        refreshClipboard()
        ComposeThread.call { tooltipMailbox.dismiss() }
        val consumed = session?.key(KeyInput(keyCode.toUiKey(), true, modifiers.toModifiers())) == true
        flushClipboard()
        flushOreFeedback()
        if (!consumed && keyCode == GLFW.GLFW_KEY_ESCAPE) onClose()
        return true
    }
    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        session?.key(KeyInput(keyCode.toUiKey(), false, modifiers.toModifiers()))
        flushClipboard()
        flushOreFeedback()
        return true
    }
    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        characters.accept(codePoint)?.let { session?.commitText(it) }
        return true
    }

    override fun onClose() { Minecraft.getInstance().setScreen(parent) }
    override fun removed() {
        try { session?.close() } finally {
            session = null
            characters.reset()
            try {
                try {
                    nativeItems?.let { items ->
                        try { items.close() } finally { closedItemStatistics = items.statistics }
                    }
                } finally {
                    nativeItems = null
                    try {
                        nativeTooltips?.let { tooltips ->
                            try { tooltips.close() } finally { closedTooltipStatistics = tooltips.statistics }
                        }
                    } finally {
                        nativeTooltips = null
                        renderer?.let { backend ->
                            try { backend.close() } finally { closedStatistics = backend.statistics }
                        }
                    }
                }
            } finally {
                renderer = null
                super.removed()
            }
        }
    }
    override fun isPauseScreen(): Boolean = false

    /** The ordinary host follows OS focus; an automated fixture can supply its own logical focus. */
    protected open fun isUiWindowFocused(): Boolean = Minecraft.getInstance().isWindowActive

    private fun updateWindowFocus() {
        val now = isUiWindowFocused()
        if (now == focused) return
        characters.reset()
        focused = now
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

    private fun currentMetrics(): ScreenMetrics {
        val window = Minecraft.getInstance().window
        return ScreenMetrics(window.width.coerceAtLeast(1), window.height.coerceAtLeast(1),
            width.coerceAtLeast(1), height.coerceAtLeast(1), window.guiScale.toFloat(), guiUnitsPerDp, minimumUiDensity)
    }
    private fun pointer(action: PointerAction, x: Double, y: Double, button: MouseButton? = null, scrollX: Float = 0f, scrollY: Float = 0f): Boolean {
        val current = metrics ?: return false
        if (action == PointerAction.PRESS || action == PointerAction.SCROLL || action == PointerAction.EXIT) {
            ComposeThread.call { tooltipMailbox.dismiss() }
        }
        val consumed = session?.pointer(PointerInput(action, current.pixelX(x), current.pixelY(y), button, scrollX, scrollY,
            Modifiers(hasShiftDown(), hasControlDown(), hasAltDown()))) ?: false
        flushOreFeedback()
        return consumed
    }
}

private fun Int.toMouseButton(): MouseButton? = when (this) {
    GLFW.GLFW_MOUSE_BUTTON_LEFT -> MouseButton.LEFT
    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> MouseButton.RIGHT
    GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE
    else -> null
}
private fun Int.toModifiers() = Modifiers(
    shift = and(GLFW.GLFW_MOD_SHIFT) != 0, control = and(GLFW.GLFW_MOD_CONTROL) != 0,
    alt = and(GLFW.GLFW_MOD_ALT) != 0, meta = and(GLFW.GLFW_MOD_SUPER) != 0,
)
private fun Int.toUiKey(): UiKey = when (this) {
    GLFW.GLFW_KEY_A -> UiKey.A; GLFW.GLFW_KEY_C -> UiKey.C; GLFW.GLFW_KEY_V -> UiKey.V; GLFW.GLFW_KEY_X -> UiKey.X; GLFW.GLFW_KEY_Y -> UiKey.Y; GLFW.GLFW_KEY_Z -> UiKey.Z
    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> UiKey.ENTER; GLFW.GLFW_KEY_ESCAPE -> UiKey.ESCAPE; GLFW.GLFW_KEY_TAB -> UiKey.TAB; GLFW.GLFW_KEY_SPACE -> UiKey.SPACE
    GLFW.GLFW_KEY_BACKSPACE -> UiKey.BACKSPACE; GLFW.GLFW_KEY_DELETE -> UiKey.DELETE
    GLFW.GLFW_KEY_LEFT -> UiKey.LEFT; GLFW.GLFW_KEY_RIGHT -> UiKey.RIGHT; GLFW.GLFW_KEY_UP -> UiKey.UP; GLFW.GLFW_KEY_DOWN -> UiKey.DOWN
    GLFW.GLFW_KEY_HOME -> UiKey.HOME; GLFW.GLFW_KEY_END -> UiKey.END; GLFW.GLFW_KEY_PAGE_UP -> UiKey.PAGE_UP; GLFW.GLFW_KEY_PAGE_DOWN -> UiKey.PAGE_DOWN
    else -> UiKey.UNKNOWN
}
