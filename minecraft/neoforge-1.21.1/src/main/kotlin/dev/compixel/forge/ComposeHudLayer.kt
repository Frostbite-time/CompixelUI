package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.item.NativeItemStatistics
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.host.UiSession
import dev.compixel.render.*
import dev.compixel.ui.ore.theme.OreThemeId
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.LayeredDraw

/**
 * Compose content drawn as a HUD layer. Register it through `RegisterGuiLayersEvent` like any other layer: it draws
 * over the game view at that position, beneath open screens, and hides with the vanilla HUD. It never receives input:
 * pointer, keys and text stay with the game or the open screen, so its content never has window focus.
 *
 * The session opens on the first drawn frame, follows window, GUI scale and resource changes, and closes when the
 * player leaves the world or on [close]; the next drawn frame opens a new one. All members run on the game thread.
 */
class ComposeHudLayer(
    private val guiUnitsPerDp: Float = 1f,
    val renderBackend: RenderBackend = configuredRenderBackend(),
    private val nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    private val nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    private val minimumUiDensity: Float = 1f,
    private val theme: OreThemeId = OreThemeId.Default,
    private val content: @Composable () -> Unit,
) : LayeredDraw.Layer {
    // Created by the first drawn frame, not while Minecraft registers layers during startup.
    private var layer: ComposeLayer? = null
    internal val session: UiSession?
        get() = layer?.session

    val nativeDrawingStatistics
        get() = layer?.nativeDrawingStatistics ?: NativeImageStatistics()

    val nativeItemStatistics: NativeItemStatistics
        get() = layer?.nativeItemStatistics ?: NativeItemStatistics()

    val rendererStatistics: RendererStatistics
        get() = layer?.rendererStatistics ?: RendererStatistics(renderBackend, 0, 0, 0)

    /** Opt-in per-frame history. Read snapshots on the game/render thread. */
    val frameProfiler: UiFrameProfiler?
        get() = layer?.frameProfiler

    init {
        require(guiUnitsPerDp.isFinite() && guiUnitsPerDp > 0f)
        require(minimumUiDensity.isFinite() && minimumUiDensity >= 0f)
    }

    override fun render(guiGraphics: GuiGraphics, deltaTracker: DeltaTracker) {
        // NeoForge draws registered layers even while the vanilla HUD is hidden.
        if (Minecraft.getInstance().options.hideGui) return
        draw(guiGraphics)
    }

    private fun draw(guiGraphics: GuiGraphics) {
        val window = Minecraft.getInstance().window
        val active =
            layer
                ?: ComposeLayer(
                        renderBackend,
                        guiUnitsPerDp,
                        nativeItemOptions,
                        minimumUiDensity,
                        theme = theme,
                        nativeDrawingOptions = nativeDrawingOptions,
                        // Without input the content never takes focus, or with it Minecraft's text input.
                        windowFocused = { false },
                        content = content,
                    )
                    .also { layer = it }
        if (active.session == null) {
            active.open(window.guiScaledWidth, window.guiScaledHeight)
            openHudLayers += this
        }
        active.render(guiGraphics, window.guiScaledWidth, window.guiScaledHeight)
    }

    /** Releases the session and every owned resource. The next drawn frame opens a new session. */
    fun close() {
        openHudLayers -= this
        layer?.close()
    }
}

/** HUD layers with an open session. The client closes them when the player leaves the world. */
private val openHudLayers = LinkedHashSet<ComposeHudLayer>()

/** Called when the player leaves the world: every HUD layer releases its session. */
internal fun closeHudLayers() = openHudLayers.toList().forEach(ComposeHudLayer::close)
