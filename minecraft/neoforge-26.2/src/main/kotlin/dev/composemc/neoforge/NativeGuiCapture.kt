package dev.composemc.neoforge

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.GpuFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.state.gui.GuiRenderState

/** Draws a native 16x16 GUI command stream into an owned image target. */
internal class NativeGuiCapture(private val imageSize: Int) : AutoCloseable {
    private val minecraft = Minecraft.getInstance()
    private val state = GuiRenderState()
    private val renderer = GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), emptyList())
    private val target = TextureTarget("composemc-native-icon", imageSize, imageSize,
        true, GpuFormat.RGBA8_UNORM)
    private var closed = false

    /** Completion may arrive on another thread; the recipient must only enqueue bytes. */
    fun capture(icon: ItemIcon, completed: (ByteArray) -> Unit) {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        val device = RenderSystem.getDevice()
        val colorTexture = checkNotNull(target.colorTexture)
        val depthTexture = checkNotNull(target.depthTexture)
        device.createCommandEncoder().clearColorAndDepthTextures(
            colorTexture, GuiRenderer.CLEAR_COLOR, depthTexture, 0.0)
        try {
            val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
            // GuiRenderer projects against the window. Map its 16x16 local area onto this target.
            graphics.pose().scale(minecraft.window.guiScaledWidth / 16f, minecraft.window.guiScaledHeight / 16f)
            icon.drawing?.accept(graphics) ?: run {
                graphics.fakeItem(icon.stack, 0, 0)
                graphics.itemDecorations(minecraft.font, icon.stack, 0, 0)
            }
            NativeGuiTargetScope.renderTo(target) { renderer.render() }
        } finally {
            renderer.endFrame()
            state.reset()
        }

        val rowBytes = imageSize * 4
        val bytes = rowBytes * imageSize
        val buffer = device.createBuffer({ "composemc-native-icon-readback" }, 9, bytes.toLong())
        device.createCommandEncoder().copyTextureToBuffer(colorTexture, buffer, 0L, {
            try {
                buffer.map(true, false).use { mapped ->
                    val source = mapped.data()
                    val pixels = ByteArray(bytes)
                    for (row in 0 until imageSize) {
                        val sourceStart = (imageSize - 1 - row) * rowBytes
                        for (column in 0 until rowBytes) pixels[row * rowBytes + column] = source.get(sourceStart + column)
                    }
                    completed(pixels)
                }
            } finally {
                buffer.close()
            }
        }, 0)
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
