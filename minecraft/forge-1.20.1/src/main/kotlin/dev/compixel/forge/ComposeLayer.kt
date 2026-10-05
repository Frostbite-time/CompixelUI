package dev.compixel.forge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.forge.drawing.*
import dev.compixel.forge.input.toModifiers
import dev.compixel.forge.input.toMouseButton
import dev.compixel.forge.input.uiKey
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
import dev.compixel.forge.theme.ThemeReloadListener
import dev.compixel.host.ScreenMetrics
import dev.compixel.host.UiLayer
import dev.compixel.host.UiLayerSurface
import dev.compixel.host.UiStateBinding
import dev.compixel.platform.KeyInput
import dev.compixel.platform.Modifiers
import dev.compixel.render.NativeImageStatistics
import dev.compixel.render.RenderBackend
import dev.compixel.render.UiFrameProfiler
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import dev.compixel.ui.theme.ThemeCatalog
import dev.compixel.ui.theme.ThemeId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
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
    theme: ThemeId = ThemeId.Default,
    design: UiDesign = OreDesign,
    private val nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    windowFocused: () -> Boolean,
    /** Adapter-owned layout-to-snapshot handoff, on the game thread before presentation. */
    prepareFrameContent: () -> Boolean = { false },
    /** The content's game state, opened before each session composes and closed with it. */
    contentState: UiStateBinding<*, *>,
    /** Closes the host when its content asks to. */
    closeHost: () -> Unit = {},
    content: @Composable () -> Unit,
) :
    UiLayer<GuiGraphics, ScreenRenderDestination>(
        renderBackend,
        guiUnitsPerDp,
        minimumUiDensity,
        theme,
        design,
        windowFocused,
        prepareFrameContent,
        contentState,
        closeHost,
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

    override fun pointerModifiers() = Modifiers(Screen.hasShiftDown(), Screen.hasControlDown(), Screen.hasAltDown())

    override val resourceEpoch: Long
        get() = RendererResources.epoch

    override val themes: ThemeCatalog
        get() = ThemeReloadListener.catalog

    override fun warn(message: String) = logger.warn("{}", message)

    override fun createSurface(profiler: UiFrameProfiler?): UiLayerSurface<ScreenRenderDestination> {
        val backend = createScreenRenderer(renderBackend, profiler)
        try {
            val items = NativeItemAtlas(backend, itemMailbox, nativeItemOptions)
            val drawings = NativeDrawingRenderer(backend, drawingMailbox, nativeDrawingOptions)
            val tooltips = NativeTooltipRenderer(backend, tooltipMailbox)
            nativeItems = items
            nativeDrawings = drawings
            nativeTooltips = tooltips
            return UiLayerSurface(backend, items, tooltips, drawings)
        } catch (error: Throwable) {
            backend.close()
            throw error
        }
    }

    override fun destination(graphics: GuiGraphics, metrics: ScreenMetrics) = ScreenRenderDestination(graphics, metrics)

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

    // Native item capture draws through GuiGraphics; draw what the screen batched first.
    override fun beforePrepare(graphics: GuiGraphics?) {
        graphics?.flush()
    }

    // OpenGL composites directly, above everything batched so far.
    override fun beforePresent(graphics: GuiGraphics) = graphics.flush()

    override fun profileResources(profiler: UiFrameProfiler) {
        val items = nativeItems?.statistics ?: return
        profiler.resources(
            items.activeVariants,
            items.cachedImages,
            items.pendingImages,
            nativeTooltips?.statistics?.visible ?: false,
        )
    }

    fun press(x: Double, y: Double, button: Int): Boolean = press(x, y, button.toMouseButton())

    fun release(x: Double, y: Double, button: Int): Boolean = release(x, y, button.toMouseButton())

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        key(KeyInput(uiKey(keyCode, scanCode), true, modifiers.toModifiers()))

    fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        key(KeyInput(uiKey(keyCode, scanCode), false, modifiers.toModifiers()))

    fun charTyped(character: Char): Boolean = typeCharacter(character)

    // No IME support: Minecraft 1.20.1 has neither text input control nor preedit events. The 26.x
    // layers add text input and composition hooks here (see their MinecraftTextInput.kt); keep this
    // difference when comparing or syncing adapters.
}
