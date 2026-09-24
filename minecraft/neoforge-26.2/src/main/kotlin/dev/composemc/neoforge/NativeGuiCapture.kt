package dev.composemc.neoforge

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.textures.GpuTexture
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
    private val targets = List(buffers) {
        TextureTarget("composemc-native-gui", imageWidth, imageHeight, true, GpuFormat.RGBA8_UNORM)
    }
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
        val target = targets[0]
        val region = drawInto(target, logicalWidth, logicalHeight) { graphics ->
            draw(graphics)?.also { require(it.first in 1..imageWidth && it.second in 1..imageHeight) }
        } ?: return false
        val device = RenderSystem.getDevice()
        val colorTexture = checkNotNull(target.colorTexture)

        val rowBytes = region.first * 4
        val bytes = rowBytes * region.second
        val buffer = device.createBuffer({ "composemc-native-gui-readback" }, 9, bytes.toLong())
        // The GUI's top-left region occupies the final texture rows in readback order.
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
        }, 0, 0, imageHeight - region.second, region.first, region.second)
        return true
    }

    /** Draws into [buffer] without a readback, for a GPU snapshot. */
    fun render(buffer: Int, logicalWidth: Int, logicalHeight: Int, draw: (GuiGraphicsExtractor) -> Boolean): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return drawInto(targets[buffer], logicalWidth, logicalHeight) { graphics -> if (draw(graphics)) Unit else null } != null
    }

    fun texture(buffer: Int): GpuTexture = checkNotNull(targets[buffer].colorTexture)

    private fun <T : Any> drawInto(target: TextureTarget, logicalWidth: Int, logicalHeight: Int,
                                   draw: (GuiGraphicsExtractor) -> T?): T? {
        require(logicalWidth > 0 && logicalHeight > 0)
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            checkNotNull(target.colorTexture), GuiRenderer.CLEAR_COLOR, checkNotNull(target.depthTexture), 0.0)
        try {
            val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
            graphics.pose().scale(minecraft.window.guiScaledWidth / logicalWidth.toFloat(),
                minecraft.window.guiScaledHeight / logicalHeight.toFloat())
            val result = draw(graphics)
            if (result != null) NativeGuiTargetScope.renderTo(renderer, target) { renderer.render() }
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
            targets.forEach { it.destroyBuffers() }
        }
    }
}
