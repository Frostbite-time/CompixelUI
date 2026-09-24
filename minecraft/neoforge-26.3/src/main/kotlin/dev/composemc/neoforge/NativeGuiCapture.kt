package dev.composemc.neoforge

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.state.gui.GuiRenderState

/** Draws native GUI command streams into owned image targets; one renderer serves every buffer. */
internal class NativeGuiCapture(private val imageWidth: Int, private val imageHeight: Int, buffers: Int = 1) : AutoCloseable {
    constructor(imageSize: Int) : this(imageSize, imageSize)
    init { require(imageWidth > 0 && imageHeight > 0 && buffers > 0) }
    private val minecraft = Minecraft.getInstance()
    private val state = GuiRenderState()
    private val renderer = GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), emptyList())
    private val targets = arrayOfNulls<TextureTarget>(buffers)
    private var closed = false

    /** Completion may arrive on another thread; the recipient must only enqueue bytes. */
    fun capture(icon: ItemIcon, completed: (ByteArray) -> Unit) {
        capture(16, 16, { graphics ->
            icon.drawing?.accept(graphics) ?: run {
                graphics.fakeItem(icon.stack, 0, 0)
                graphics.itemDecorations(minecraft.font, icon.stack, 0, 0)
            }
            imageWidth to imageHeight
        }) { pixels, _, _ -> completed(pixels) }
    }

    /** The draw callback returns the top-left pixel region to publish, or null to cancel. */
    fun capture(logicalWidth: Int, logicalHeight: Int,
                draw: (GuiGraphicsExtractor) -> Pair<Int, Int>?,
                completed: (ByteArray, Int, Int) -> Unit): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        val region = drawInto(0, logicalWidth, logicalHeight, { it }) { graphics ->
            draw(graphics)?.also { require(it.first in 1..imageWidth && it.second in 1..imageHeight) }
        } ?: return false

        val device = RenderSystem.getDevice()
        val colorTexture = checkNotNull(targets[0]?.colorTexture)
        val rowBytes = region.first * 4
        val bytes = rowBytes * region.second
        val buffer = device.createBuffer({ "composemc-native-gui-readback" }, 9, bytes.toLong())
        // The target matches the measured region; readback rows still arrive bottom-up.
        device.createCommandEncoder().copyTextureToBuffer(colorTexture, buffer, 0L, {
            try {
                buffer.map(true, false).use { mapped ->
                    val source = mapped.data()
                    val pixels = ByteArray(bytes)
                    for (row in 0 until region.second) {
                        val sourceStart = (region.second - 1 - row) * rowBytes
                        for (column in 0 until rowBytes) pixels[row * rowBytes + column] = source.get(sourceStart + column)
                    }
                    completed(pixels, region.first, region.second)
                }
            } finally {
                buffer.close()
            }
        }, 0, 0, 0, region.first, region.second)
        return true
    }

    /** Draws into [buffer] without a readback and returns the measured content, or null to cancel. */
    fun <T : Any> render(buffer: Int, logicalWidth: Int, logicalHeight: Int, draw: (GuiGraphicsExtractor) -> T?): T? {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return drawInto(buffer, logicalWidth, logicalHeight, { imageWidth to imageHeight }, draw)
    }

    /** Extracts first, then creates a target matching the returned content dimensions. */
    fun <T : Any> renderSized(buffer: Int, logicalWidth: Int, logicalHeight: Int,
                              size: (T) -> Pair<Int, Int>, draw: (GuiGraphicsExtractor) -> T?): T? {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return drawInto(buffer, logicalWidth, logicalHeight, size, draw)
    }

    fun texture(buffer: Int): GpuTexture = checkNotNull(targets[buffer]?.colorTexture)

    private fun target(buffer: Int, width: Int, height: Int): TextureTarget {
        require(width in 1..imageWidth && height in 1..imageHeight)
        val old = targets[buffer]
        if (old?.colorTexture?.getWidth(0) == width && old.colorTexture?.getHeight(0) == height) return old
        val fresh = TextureTarget("composemc-native-gui", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)
        targets[buffer] = fresh
        old?.let { FrameRetirement.afterFrame { it.destroyBuffers() } }
        return fresh
    }

    private fun <T : Any> drawInto(buffer: Int, logicalWidth: Int, logicalHeight: Int,
                                   size: (T) -> Pair<Int, Int>, draw: (GuiGraphicsExtractor) -> T?): T? {
        require(logicalWidth > 0 && logicalHeight > 0)
        try {
            val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
            // GuiRenderer projects against the window; map the local GUI area onto this target.
            graphics.pose().scale(minecraft.window.guiScaledWidth / logicalWidth.toFloat(),
                minecraft.window.guiScaledHeight / logicalHeight.toFloat())
            val result = draw(graphics)
            if (result != null) {
                val dimensions = size(result)
                val output = target(buffer, dimensions.first, dimensions.second)
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    checkNotNull(output.colorTexture), GuiRenderer.CLEAR_COLOR, checkNotNull(output.depthTexture), 0.0)
                NativeGuiTargetScope.renderTo(renderer, output) { renderer.render() }
            }
            return result
        } finally {
            renderer.endFrame()
            state.reset()
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        closed = true
        FrameRetirement.afterFrame {
            renderer.close()
            targets.forEach { it?.destroyBuffers() }
        }
    }
}
