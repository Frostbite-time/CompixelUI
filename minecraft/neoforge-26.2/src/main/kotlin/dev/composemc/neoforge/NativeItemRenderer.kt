package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

data class NativeItemStatistics(
    val activeVariants: Int = 0,
    val cachedImages: Int = 0,
    val pendingImages: Int = 0,
    val preparedImages: Long = 0,
    val retiredImages: Long = 0,
    val lastRequestGeneration: Long = 0,
    val dynamicVariants: Int = 0,
    val animationRefreshes: Long = 0,
)

/**
 * Native item extraction for 26.2.
 *
 * Mojang's renderer no longer exposes an immediate GUI/FBO path. We retain
 * Compose's item layout, then extract the real item command into the same GUI
 * render state as the Compose frame. Model overrides, foil, custom renderers
 * and animated textures therefore stay on Minecraft's selected backend.
 */
internal class NativeItemRenderer(
    private val mailbox: ItemImageMailbox,
    private val options: NativeItemOptions,
) : AutoCloseable {
    private var visible = emptyList<ItemIcon>()
    private var generation = 0L
    private var prepared = 0L
    private var closed = false

    val statistics get() = NativeItemStatistics(
        activeVariants = visible.size,
        preparedImages = prepared,
        lastRequestGeneration = generation,
        dynamicVariants = visible.count { it.refresh != IconRefresh.STATIC },
    )

    fun recorded(frameGeneration: Long) {
        visible = ComposeThread.call { mailbox.activeRequests() }
        generation = frameGeneration
    }

    /** Layout commands are consumed during presentation, so no FBO image batch is prepared. */
    fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return false
    }

    fun present(graphics: GuiGraphicsExtractor, metrics: ScreenMetrics) {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        val positioned = ComposeThread.call { mailbox.positionedRequests() }
        val minecraft = Minecraft.getInstance()
        positioned.forEach { entry ->
            val left = (entry.bounds.left * metrics.guiWidth / metrics.framebufferWidth).roundToInt()
            val top = (entry.bounds.top * metrics.guiHeight / metrics.framebufferHeight).roundToInt()
            val right = (entry.bounds.right * metrics.guiWidth / metrics.framebufferWidth).roundToInt()
            val bottom = (entry.bounds.bottom * metrics.guiHeight / metrics.framebufferHeight).roundToInt()
            if (right <= left || bottom <= top) return@forEach
            graphics.nextStratum()
            graphics.enableScissor(
                (entry.clip.left * metrics.guiWidth / metrics.framebufferWidth).roundToInt(),
                (entry.clip.top * metrics.guiHeight / metrics.framebufferHeight).roundToInt(),
                (entry.clip.right * metrics.guiWidth / metrics.framebufferWidth).roundToInt(),
                (entry.clip.bottom * metrics.guiHeight / metrics.framebufferHeight).roundToInt(),
            )
            try {
                graphics.pose().pushMatrix()
                try {
                    graphics.pose().translate(left.toFloat(), top.toFloat())
                    graphics.pose().scale((right - left) / 16f, (bottom - top) / 16f)
                    entry.icon.drawing?.accept(graphics) ?: run {
                        graphics.fakeItem(entry.icon.stack, 0, 0)
                        graphics.itemDecorations(minecraft.font, entry.icon.stack, 0, 0)
                    }
                } finally {
                    graphics.pose().popMatrix()
                }
            } finally {
                graphics.disableScissor()
            }
        }
        prepared += positioned.size
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        visible = emptyList()
        ComposeThread.call { mailbox.clear() }
    }

    override fun close() {
        if (closed) return
        reset()
        closed = true
    }
}
