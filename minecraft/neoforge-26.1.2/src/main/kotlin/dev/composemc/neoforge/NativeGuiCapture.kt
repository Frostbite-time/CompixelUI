package dev.composemc.neoforge

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.state.gui.GuiRenderState

/** Draws a native GUI command stream into an owned image target. */
internal class NativeGuiCapture(private val imageWidth: Int, private val imageHeight: Int) : AutoCloseable {
    constructor(imageSize: Int) : this(imageSize, imageSize)
    init { require(imageWidth > 0 && imageHeight > 0) }
    private val minecraft = Minecraft.getInstance()
    private val state = GuiRenderState()
    private val renderer = GuiRenderer(state, minecraft.renderBuffers().bufferSource(),
        minecraft.gameRenderer.submitNodeStorage, minecraft.gameRenderer.featureRenderDispatcher, emptyList())
    private val target = TextureTarget("composemc-native-gui", imageWidth, imageHeight, true)
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
        require(logicalWidth > 0 && logicalHeight > 0)
        val device = RenderSystem.getDevice()
        val colorTexture = checkNotNull(target.colorTexture)
        val depthTexture = checkNotNull(target.depthTexture)
        device.createCommandEncoder().clearColorAndDepthTextures(
            colorTexture, GuiRenderer.CLEAR_COLOR, depthTexture, 0.0)
        val region = try {
            val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
            // GuiRenderer projects against the window; map the local GUI area onto this target.
            graphics.pose().scale(minecraft.window.guiScaledWidth / logicalWidth.toFloat(),
                minecraft.window.guiScaledHeight / logicalHeight.toFloat())
            val measured = draw(graphics)
            if (measured != null) {
                require(measured.first in 1..imageWidth && measured.second in 1..imageHeight)
                NativeGuiTargetScope.renderTo(renderer, target) { renderer.render(checkNotNull(RenderSystem.getShaderFog())) }
            }
            measured
        } finally {
            renderer.endFrame()
            state.reset()
        }
        if (region == null) return false

        val rowBytes = region.first * 4
        val bytes = rowBytes * region.second
        val buffer = device.createBuffer({ "composemc-native-gui-readback" }, 9, bytes.toLong())
        // The GUI's top-left region occupies the final texture rows in readback order.
        device.createCommandEncoder().copyTextureToBuffer(colorTexture, buffer, 0L, {
            try {
                device.createCommandEncoder().mapBuffer(buffer, true, false).use { mapped ->
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

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        closed = true
        FrameRetirement.afterFrame {
            renderer.close()
            target.destroyBuffers()
        }
    }
}
