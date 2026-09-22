package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
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

/** Defers the game's rich tooltip to the 26.2 GUI extraction pass. */
internal class NativeTooltipRenderer(
    private val mailbox: ItemTooltipMailbox,
) : AutoCloseable {
    private var request: ItemTooltipRequest? = null
    private var generation = 0L
    private var prepared = 0L
    private var closed = false

    val statistics get() = NativeTooltipStatistics(
        visible = request != null,
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
        val active = request ?: return
        val bounds = ComposeThread.call { mailbox.bounds } ?: return
        val x = (bounds.left * metrics.guiWidth / metrics.framebufferWidth).roundToInt()
        val y = (bounds.top * metrics.guiHeight / metrics.framebufferHeight).roundToInt()
        graphics.setTooltipForNextFrame(Minecraft.getInstance().font, active.icon.stack, x, y)
        prepared++
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        request = null
        ComposeThread.call { mailbox.clearImage() }
    }

    override fun close() {
        if (closed) return
        reset()
        closed = true
    }
}
