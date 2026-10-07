package dev.compixel.forge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.forge.drawing.*
import dev.compixel.forge.input.MinecraftTextInput
import dev.compixel.forge.input.toModifiers
import dev.compixel.forge.input.toMouseButton
import dev.compixel.forge.input.toUiKey
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.ItemTooltipMailbox
import dev.compixel.forge.item.LocalItemImages
import dev.compixel.forge.item.LocalItemTooltips
import dev.compixel.forge.item.NativeItemAtlas
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.item.NativeItemStatistics
import dev.compixel.forge.item.NativeTooltipRenderer
import dev.compixel.forge.item.NativeTooltipStatistics
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.render.ScreenRenderDestination
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.forge.render.createScreenRenderer
import dev.compixel.forge.theme.ColorSchemes
import dev.compixel.host.ScreenMetrics
import dev.compixel.host.UiLayer
import dev.compixel.host.UiLayerSurface
import dev.compixel.host.UiSession
import dev.compixel.host.UiStateBinding
import dev.compixel.platform.KeyInput
import dev.compixel.platform.Modifiers
import dev.compixel.render.NativeImageStatistics
import dev.compixel.render.RenderBackend
import dev.compixel.render.UiFrameProfiler
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import dev.compixel.ui.theme.Schemes
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent // IME
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * The shared [UiLayer] on Minecraft: the window, clipboard, click sound, item icons, tooltips and native drawings, and
 * Minecraft's input events. Every member runs on the game thread.
 */
internal class ComposeLayer(
    renderBackend: RenderBackend = configuredRenderBackend(),
    guiUnitsPerDp: Float = 1f,
    private val nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    minimumUiDensity: Float = 1f,
    design: UiDesign = OreDesign(),
    private val nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    windowFocused: () -> Boolean,
    /** Adapter-owned layout-to-snapshot handoff, on the game thread before presentation. */
    prepareFrameContent: () -> Boolean = { false },
    /** The content's game state, opened before each session composes and closed with it. */
    contentState: UiStateBinding<*, *>,
    /** Closes the host when its content asks to. */
    closeHost: () -> Unit = {},
    /** Opens the color editor over the host; null where none can open, as in HUD layers. */
    openColorEditor: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) :
    UiLayer<GuiGraphicsExtractor, ScreenRenderDestination>(
        renderBackend,
        guiUnitsPerDp,
        minimumUiDensity,
        design,
        windowFocused,
        prepareFrameContent,
        contentState,
        closeHost,
        openColorEditor,
        content,
    ) {
    private val logger = LogUtils.getLogger()
    private val itemMailbox = ComposeThread.call { NativeImageMailbox<ItemIcon> { it.id } }
    private val drawingMailbox = ComposeThread.call { NativeImageMailbox<NativeDrawing> { it.id } }
    private val tooltipMailbox = ComposeThread.call { ItemTooltipMailbox() }
    // The latest session's, kept after it closes so their final statistics stay readable.
    private var nativeItems: NativeItemAtlas? = null
    private var nativeTooltips: NativeTooltipRenderer? = null
    private var nativeDrawings: NativeDrawingRenderer? = null

    val nativeItemStatistics: NativeItemStatistics
        get() = nativeItems?.statistics ?: NativeItemStatistics()

    val nativeTooltipStatistics: NativeTooltipStatistics
        get() = nativeTooltips?.statistics ?: NativeTooltipStatistics()

    val nativeDrawingStatistics: NativeImageStatistics
        get() = nativeDrawings?.statistics ?: NativeImageStatistics()

    val nativeTooltipBounds
        get() = ComposeThread.call { tooltipMailbox.bounds }

    // IME support (26.x only). Minecraft's text input and input method composition are handled in
    // MinecraftTextInput.kt; 1.20.1 and 1.21.1 have neither, so their layers lack every line marked
    // "IME". Keep those lines when comparing or syncing this file with the other adapters.
    private val textInput = MinecraftTextInput() // IME
    /** Whether Compose holds Minecraft's text input for a focused text field. */
    val textInputOpen: Boolean
        get() = textInput.open // IME

    override fun assertRenderThread() = RenderSystem.assertOnRenderThread()

    override val framebufferWidth: Int
        get() = Minecraft.getInstance().window.width

    override val framebufferHeight: Int
        get() = Minecraft.getInstance().window.height

    override val guiScale: Float
        get() = Minecraft.getInstance().window.guiScale.toFloat()

    override var systemClipboard: String
        get() = Minecraft.getInstance().keyboardHandler.clipboard
        set(text) {
            Minecraft.getInstance().keyboardHandler.clipboard = text
        }

    override fun playFeedback() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
    }

    override fun pointerModifiers() =
        Minecraft.getInstance().let { Modifiers(it.hasShiftDown(), it.hasControlDown(), it.hasAltDown()) }

    override val resourceEpoch: Long
        get() = RendererResources.epoch

    override val schemes: Schemes
        get() = ColorSchemes.current

    override fun warn(message: String) = logger.warn("{}", message)

    override fun createSurface(profiler: UiFrameProfiler?): UiLayerSurface<ScreenRenderDestination> {
        val backend = createScreenRenderer(renderBackend, profiler)
        try {
            val items = NativeItemAtlas(itemMailbox, nativeItemOptions, backend.nativeSnapshots)
            val drawings = NativeDrawingRenderer(drawingMailbox, nativeDrawingOptions, backend.nativeSnapshots)
            val tooltips = NativeTooltipRenderer(tooltipMailbox, backend.nativeSnapshots)
            nativeItems = items
            nativeDrawings = drawings
            nativeTooltips = tooltips
            return UiLayerSurface(backend, items, tooltips, drawings)
        } catch (error: Throwable) {
            backend.close()
            throw error
        }
    }

    override fun destination(graphics: GuiGraphicsExtractor, metrics: ScreenMetrics) =
        ScreenRenderDestination(graphics, metrics)

    @Composable
    override fun ProvideContent(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalItemImages provides itemMailbox,
            LocalNativeDrawings provides drawingMailbox,
            LocalItemTooltips provides tooltipMailbox,
            content = content,
        )
    }

    override fun dismissTransient() = ComposeThread.call { tooltipMailbox.dismiss() }

    override fun afterFrame(session: UiSession, metrics: ScreenMetrics) = textInput.afterFrame(session, metrics) // IME

    // A press discards an unconfirmed composition.
    override fun beforePress(session: UiSession?) = textInput.beforePress(session) // IME

    override fun beforeClose() = textInput.close() // IME

    override fun profileResources(profiler: UiFrameProfiler) {
        val items = nativeItems?.statistics ?: return
        profiler.resources(
            items.activeVariants,
            items.cachedImages,
            items.pendingImages,
            nativeTooltips?.statistics?.visible ?: false,
        )
    }

    /** Records and renders the next frame during extraction, before anything beneath Compose is extracted. */
    fun prepare(width: Int, height: Int) = prepare(null, width, height)

    fun press(x: Double, y: Double, button: Int): Boolean = press(x, y, button.toMouseButton())

    fun release(x: Double, y: Double, button: Int): Boolean = release(x, y, button.toMouseButton())

    fun keyPressed(event: KeyEvent): Boolean = key(KeyInput(event.toUiKey(), true, event.modifiers().toModifiers()))

    fun keyReleased(event: KeyEvent): Boolean = key(KeyInput(event.toUiKey(), false, event.modifiers().toModifiers()))

    fun charTyped(event: CharacterEvent): Boolean = typeText(event.codepointAsString())

    /** IME: called by the host after its native focus changes; see [MinecraftTextInput]. */
    fun nativeFocusChanged(focused: Boolean) = textInput.nativeFocusChanged(session, focused) // IME

    /** IME: the input method's composition for the focused text field; see [MinecraftTextInput]. */
    fun preedit(event: PreeditEvent?): Boolean = textInput.preedit(session, event) // IME
}
