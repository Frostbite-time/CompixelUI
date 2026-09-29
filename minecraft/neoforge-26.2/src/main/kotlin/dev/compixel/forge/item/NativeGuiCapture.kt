package dev.compixel.forge.item

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import dev.compixel.forge.render.FrameRetirement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.state.gui.GuiRenderState

/** Draws native GUI command streams into owned image targets; one renderer serves every buffer. */
internal class NativeGuiCapture(private val imageWidth: Int, private val imageHeight: Int, buffers: Int = 1) :
    AutoCloseable {
    constructor(imageSize: Int) : this(imageSize, imageSize)

    init {
        require(imageWidth > 0 && imageHeight > 0 && buffers > 0)
    }

    private val minecraft = Minecraft.getInstance()
    private val state = GuiRenderState()
    private val renderer = GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), emptyList())
    private val targets = arrayOfNulls<TextureTarget>(buffers)
    private var closed = false

    /**
     * The draw callback returns the top-left pixel region to publish, or null to cancel. Completion may arrive on
     * another thread; the recipient must only enqueue bytes.
     */
    fun capture(
        logicalWidth: Int,
        logicalHeight: Int,
        draw: (GuiGraphicsExtractor) -> Pair<Int, Int>?,
        completed: (ByteArray, Int, Int) -> Unit,
    ): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        drawInto(0, logicalWidth, logicalHeight, { it }) { graphics ->
            draw(graphics)?.also { require(it.first in 1..imageWidth && it.second in 1..imageHeight) }
        } ?: return false
        // The target matches the measured region.
        readback(0, completed)
        return true
    }

    /** Copies the whole of [buffer] with top-down rows. Completion may arrive on another thread. */
    fun readback(buffer: Int, completed: (ByteArray, Int, Int) -> Unit) {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        val device = RenderSystem.getDevice()
        val colorTexture = texture(buffer)
        val width = colorTexture.getWidth(0)
        val height = colorTexture.getHeight(0)
        val rowBytes = width * 4
        val bytes = rowBytes * height
        val readback = device.createBuffer({ "compixel-native-gui-readback" }, 9, bytes.toLong())
        // Readback rows arrive bottom-up.
        device
            .createCommandEncoder()
            .copyTextureToBuffer(
                colorTexture,
                readback,
                0L,
                {
                    try {
                        readback.map(true, false).use { mapped ->
                            val source = mapped.data()
                            val pixels = ByteArray(bytes)
                            for (row in 0 until height) {
                                val sourceStart = (height - 1 - row) * rowBytes
                                for (column in 0 until rowBytes) pixels[row * rowBytes + column] =
                                    source.get(sourceStart + column)
                            }
                            completed(pixels, width, height)
                        }
                    } finally {
                        readback.close()
                    }
                },
                0,
                0,
                0,
                width,
                height,
            )
    }

    /** Draws into [buffer] without a readback and returns the measured content, or null to cancel. */
    fun <T : Any> render(buffer: Int, logicalWidth: Int, logicalHeight: Int, draw: (GuiGraphicsExtractor) -> T?): T? {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return drawInto(buffer, logicalWidth, logicalHeight, { imageWidth to imageHeight }, draw)
    }

    /** Extracts first, then creates a target matching the returned content dimensions. */
    fun <T : Any> renderSized(
        buffer: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        size: (T) -> Pair<Int, Int>,
        draw: (GuiGraphicsExtractor) -> T?,
    ): T? {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        return drawInto(buffer, logicalWidth, logicalHeight, size, draw)
    }

    fun texture(buffer: Int): GpuTexture = checkNotNull(targets[buffer]?.colorTexture)

    private fun target(buffer: Int, width: Int, height: Int): TextureTarget {
        require(width in 1..imageWidth && height in 1..imageHeight)
        val old = targets[buffer]
        if (old?.colorTexture?.getWidth(0) == width && old.colorTexture?.getHeight(0) == height) return old
        val fresh = TextureTarget("compixel-native-gui", width, height, true, GpuFormat.RGBA8_UNORM)
        targets[buffer] = fresh
        old?.let { FrameRetirement.afterFrame { it.destroyBuffers() } }
        return fresh
    }

    private fun <T : Any> drawInto(
        buffer: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        size: (T) -> Pair<Int, Int>,
        draw: (GuiGraphicsExtractor) -> T?,
    ): T? {
        require(logicalWidth > 0 && logicalHeight > 0)
        try {
            val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
            // GuiRenderer projects against the window; map the local GUI area onto this target.
            graphics
                .pose()
                .scale(
                    minecraft.window.guiScaledWidth / logicalWidth.toFloat(),
                    minecraft.window.guiScaledHeight / logicalHeight.toFloat(),
                )
            val result = draw(graphics)
            if (result != null) {
                val dimensions = size(result)
                val output = target(buffer, dimensions.first, dimensions.second)
                RenderSystem.getDevice()
                    .createCommandEncoder()
                    .clearColorAndDepthTextures(
                        checkNotNull(output.colorTexture),
                        GuiRenderer.CLEAR_COLOR,
                        checkNotNull(output.depthTexture),
                        0.0,
                    )
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
