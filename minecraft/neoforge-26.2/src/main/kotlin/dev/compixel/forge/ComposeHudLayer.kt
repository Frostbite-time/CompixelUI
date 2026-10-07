package dev.compixel.forge

import androidx.compose.runtime.Composable
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.forge.item.NativeItemStatistics
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.host.UiSession
import dev.compixel.host.UiStateBinding
import dev.compixel.render.*
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.neoforged.neoforge.client.gui.GuiLayer

/**
 * Compose content drawn as a HUD layer. Register it through `RegisterGuiLayersEvent` like any other layer: it draws
 * over the game view at that position, beneath open screens, and hides with the vanilla HUD. It never receives input:
 * pointer, keys and text stay with the game or the open screen, so its content never has window focus.
 *
 * The session opens on the first drawn frame, follows window, GUI scale and resource changes, and closes when the
 * player leaves the world or on [close]; the next drawn frame opens a new one. All members run on the game thread.
 *
 * A subclass reads the game into an immutable [snapshot], taken when the session opens and once per client tick, and
 * draws the latest one in [Content]. A HUD layer takes no input, so it has no actions.
 */
abstract class ComposeHudLayer<S>(
    private val guiUnitsPerDp: Float = 1f,
    val renderBackend: RenderBackend = configuredRenderBackend(),
    private val nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    private val nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    private val minimumUiDensity: Float = 1f,
    private val design: UiDesign = OreDesign(),
) : GuiLayer {
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

    /** The content's game state, opened and closed with each session. */
    internal val contentState = UiStateBinding<S, Nothing>(::snapshot) {}

    init {
        require(guiUnitsPerDp.isFinite() && guiUnitsPerDp > 0f)
        require(minimumUiDensity.isFinite() && minimumUiDensity >= 0f)
    }

    /** Reads the game on the game thread. Return an immutable value: an equal snapshot leaves the content as it is. */
    protected abstract fun snapshot(): S

    /**
     * The content for the latest [state], composed on the Compose thread. Use [state] and immutable values prepared
     * during construction, never game objects such as the player.
     */
    @Composable protected abstract fun Content(state: S)

    final override fun render(guiGraphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        // NeoForge draws registered layers even while the vanilla HUD is hidden.
        if (Minecraft.getInstance().gui.hud.isHidden) return
        draw(guiGraphics)
    }

    private fun draw(guiGraphics: GuiGraphicsExtractor) {
        val window = Minecraft.getInstance().window
        val active =
            layer
                ?: ComposeLayer(
                        renderBackend,
                        guiUnitsPerDp,
                        nativeItemOptions,
                        minimumUiDensity,
                        design = design,
                        nativeDrawingOptions = nativeDrawingOptions,
                        // Without input the content never takes focus, or with it Minecraft's text input.
                        windowFocused = { false },
                        contentState = contentState,
                        content = { Content(contentState.value) },
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

    /** Called once per client tick while the session is open. */
    internal fun tick() {
        contentState.tick()
    }
}

/** HUD layers with an open session. The client ticks them and closes them when the player leaves the world. */
private val openHudLayers = LinkedHashSet<ComposeHudLayer<*>>()

/** Called after each client tick: HUD layers publish their content's state. */
internal fun tickHudLayers() = openHudLayers.toList().forEach { it.tick() }

/** Called when the player leaves the world: every HUD layer releases its session. */
internal fun closeHudLayers() = openHudLayers.toList().forEach { it.close() }
