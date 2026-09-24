package dev.composemc.neoforge

import androidx.compose.ui.geometry.Rect
import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip
import net.neoforged.neoforge.client.event.RenderTooltipEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer
import kotlin.math.roundToInt

data class NativeTooltipStatistics(
    val visible: Boolean = false,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val components: Int = 0,
    val richComponents: Int = 0,
    val preparedImages: Long = 0,
    val retiredImages: Long = 0,
    val lastRequestGeneration: Long = 0,
)

/** Defers native tooltip drawing and observes the resulting NeoForge render event. */
internal class NativeTooltipRenderer(
    private val mailbox: ItemTooltipMailbox,
) : AutoCloseable {
    private var request: ItemTooltipRequest? = null
    private var generation = 0L
    private var prepared = 0L
    private var closed = false
    private var metrics: ScreenMetrics? = null
    private var bounds: Rect? = null
    private var components = 0
    private var richComponents = 0
    private val rendered = Consumer<RenderTooltipEvent.Texture> { event ->
        val active = request ?: return@Consumer
        if (event.itemStack !== active.icon.stack) return@Consumer
        val current = metrics ?: return@Consumer
        val font = event.font
        val lines = event.components
        val width = lines.maxOfOrNull { it.getWidth(font) } ?: return@Consumer
        val height = lines.sumOf { it.getHeight(font) }
        bounds = Rect(
            current.pixelX((event.x - 4).toDouble()), current.pixelY((event.y - 4).toDouble()),
            current.pixelX((event.x + width + 4).toDouble()), current.pixelY((event.y + height + 4).toDouble()),
        )
        components = lines.size
        richComponents = lines.count { it !is ClientTextTooltip }
        prepared++
    }

    init { NeoForge.EVENT_BUS.addListener(rendered) }

    val renderedBounds get() = bounds

    val statistics get() = NativeTooltipStatistics(
        visible = bounds != null,
        imageWidth = bounds?.width?.roundToInt() ?: 0,
        imageHeight = bounds?.height?.roundToInt() ?: 0,
        components = components,
        richComponents = richComponents,
        preparedImages = prepared,
        lastRequestGeneration = generation,
    )

    fun recorded(frameGeneration: Long) {
        request = ComposeThread.call { mailbox.request }
        generation = frameGeneration
    }

    fun prepare(now: Long, current: ScreenMetrics): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return false
    }

    fun present(graphics: GuiGraphicsExtractor, metrics: ScreenMetrics) {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        bounds = null
        components = 0
        richComponents = 0
        this.metrics = metrics
        val active = request ?: return
        val bounds = ComposeThread.call { mailbox.bounds } ?: return
        val x = (bounds.left * metrics.guiWidth / metrics.framebufferWidth).roundToInt()
        val y = (bounds.top * metrics.guiHeight / metrics.framebufferHeight).roundToInt()
        graphics.setTooltipForNextFrame(Minecraft.getInstance().font, active.icon.stack, x, y)
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        request = null
        bounds = null
        components = 0
        richComponents = 0
        metrics = null
        ComposeThread.call { mailbox.clearImage() }
    }

    override fun close() {
        if (closed) return
        NeoForge.EVENT_BUS.unregister(rendered)
        reset()
        closed = true
    }
}
