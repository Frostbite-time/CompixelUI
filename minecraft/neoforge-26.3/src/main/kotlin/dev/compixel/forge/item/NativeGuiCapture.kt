package dev.compixel.forge.item

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.compixel.forge.render.FrameRetirement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.state.gui.GuiRenderState
import org.jetbrains.skia.IRect

/**
 * Draws native GUI command streams into owned image targets, with one renderer for each GUI scale drawn, so none of
 * them discards its item cache for a different scale. Buffers are redrawn completely, while pages keep their pixels
 * between draws.
 */
internal class NativeGuiCapture(private var imageWidth: Int, private var imageHeight: Int, buffers: Int = 1) :
    AutoCloseable {
    constructor(imageSize: Int) : this(imageSize, imageSize)

    init {
        require(imageWidth > 0 && imageHeight > 0 && buffers > 0)
    }

    private val minecraft = Minecraft.getInstance()
    private val state = GuiRenderState()
    private val renderers = HashMap<Int, GuiRenderer>()
    private val targets = arrayOfNulls<TextureTarget>(buffers)
    private val pages = ArrayList<TextureTarget?>()
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
    fun readback(buffer: Int, completed: (ByteArray, Int, Int) -> Unit) = readback(texture(buffer), completed)

    /** Copies the whole of [page] with top-down rows. Completion may arrive on another thread. */
    fun readbackPage(page: Int, completed: (ByteArray, Int, Int) -> Unit) = readback(pageTexture(page), completed)

    private fun readback(colorTexture: GpuTexture, completed: (ByteArray, Int, Int) -> Unit) {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        val device = RenderSystem.getDevice()
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

    /**
     * Draws into [page], an image-sized target that keeps its pixels: only [cells], pixel rectangles with a top-left
     * origin, are cleared first. A new page starts transparent. [draw] shows 16 GUI units with [imageSize] pixels.
     */
    fun renderPage(page: Int, cells: List<IRect>, imageSize: Int, draw: (GuiGraphicsExtractor) -> Unit) {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        // The renderer projects the window and caches items at 16 pixels per GUI scale step. For a page it projects the
        // page, at the smallest scale whose cached items have at least the page's pixels: at 16 times that scale, items
        // match the game's own exactly; other sizes sample them down, as the game samples an item it draws scaled.
        val scale = (imageSize + 15) / 16
        val window = minecraft.gameRenderer.gameRenderState().windowRenderState
        val windowWidth = window.width
        val windowHeight = window.height
        val windowScale = window.guiScale
        window.width = imageWidth
        window.height = imageHeight
        window.guiScale = scale
        try {
            val units = imageSize / (16f * scale)
            renderInto(
                renderer(scale),
                units,
                units,
                { graphics ->
                    draw(graphics)
                    true
                },
            ) {
                while (pages.size <= page) pages += null
                val previous = pages[page]
                val existing = previous?.takeIf {
                    it.colorTexture?.getWidth(0) == imageWidth && it.colorTexture?.getHeight(0) == imageHeight
                }
                if (existing == null && previous != null) FrameRetirement.afterFrame { previous.destroyBuffers() }
                val output = existing ?: newTarget(imageWidth, imageHeight).also { pages[page] = it }
                if (existing == null) clear(output)
                else {
                    // GUI targets are drawn bottom-up, so a cell's region starts at its bottom edge.
                    cells.forEach { cell ->
                        clear(output, cell.left, imageHeight - cell.bottom, cell.width, cell.height)
                    }
                }
                output
            }
        } finally {
            window.width = windowWidth
            window.height = windowHeight
            window.guiScale = windowScale
        }
    }

    /**
     * Sets the image size of later draws. A page keeps its pixels at its previous size until it is drawn again; that
     * draw starts it over, transparent, at the new size.
     */
    fun resize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        imageWidth = width
        imageHeight = height
    }

    fun pageTexture(page: Int): GpuTexture = checkNotNull(pages.getOrNull(page)?.colorTexture)

    /** Copies [page] into [buffer], for a snapshot that consumes its source. */
    fun copyPage(page: Int, buffer: Int) {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        val output = target(buffer, imageWidth, imageHeight)
        RenderSystem.getDevice()
            .createCommandEncoder()
            .copyTextureToTexture(
                pageTexture(page),
                checkNotNull(output.colorTexture),
                0,
                0,
                0,
                0,
                0,
                imageWidth,
                imageHeight,
            )
    }

    /** Frees [page] once the queued work that uses it finished; drawing it again starts transparent. */
    fun discardPage(page: Int) {
        RenderSystem.assertOnRenderThread()
        val target = pages.getOrNull(page) ?: return
        pages[page] = null
        FrameRetirement.afterFrame { target.destroyBuffers() }
    }

    fun texture(buffer: Int): GpuTexture = checkNotNull(targets[buffer]?.colorTexture)

    private fun target(buffer: Int, width: Int, height: Int): TextureTarget {
        require(width in 1..imageWidth && height in 1..imageHeight)
        val old = targets[buffer]
        if (old?.colorTexture?.getWidth(0) == width && old.colorTexture?.getHeight(0) == height) return old
        val fresh = newTarget(width, height)
        targets[buffer] = fresh
        old?.let { FrameRetirement.afterFrame { it.destroyBuffers() } }
        return fresh
    }

    private fun newTarget(width: Int, height: Int) =
        TextureTarget("compixel-native-gui", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)

    private fun clear(output: TextureTarget) =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .clearColorAndDepthTextures(
                checkNotNull(output.colorTexture),
                GuiRenderer.CLEAR_COLOR,
                checkNotNull(output.depthTexture),
                0.0,
            )

    private fun clear(output: TextureTarget, x: Int, y: Int, width: Int, height: Int) =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .clearColorAndDepthTextures(
                checkNotNull(output.colorTexture),
                GuiRenderer.CLEAR_COLOR,
                checkNotNull(output.depthTexture),
                0.0,
                x,
                y,
                width,
                height,
                0,
            )

    private fun <T : Any> drawInto(
        buffer: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        size: (T) -> Pair<Int, Int>,
        draw: (GuiGraphicsExtractor) -> T?,
    ): T? {
        require(logicalWidth > 0 && logicalHeight > 0)
        // GuiRenderer projects against the window; map the local GUI area onto this target.
        return renderInto(
            renderer(minecraft.gameRenderer.gameRenderState().windowRenderState.guiScale),
            minecraft.window.guiScaledWidth / logicalWidth.toFloat(),
            minecraft.window.guiScaledHeight / logicalHeight.toFloat(),
            draw,
        ) { result ->
            val dimensions = size(result)
            target(buffer, dimensions.first, dimensions.second).also(::clear)
        }
    }

    private fun renderer(guiScale: Int) =
        renderers.getOrPut(guiScale) {
            GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), emptyList())
        }

    /**
     * Extracts [draw] with GUI coordinates scaled by [scaleX] x [scaleY], then renders it with [renderer] into the
     * target [output] selects and prepares.
     */
    private fun <T : Any> renderInto(
        renderer: GuiRenderer,
        scaleX: Float,
        scaleY: Float,
        draw: (GuiGraphicsExtractor) -> T?,
        output: (T) -> TextureTarget,
    ): T? {
        try {
            val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
            graphics.pose().scale(scaleX, scaleY)
            val result = draw(graphics)
            if (result != null) {
                val target = output(result)
                NativeGuiTargetScope.renderTo(renderer, target) { renderer.render() }
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
            renderers.values.forEach { it.close() }
            targets.forEach { it?.destroyBuffers() }
            pages.forEach { it?.destroyBuffers() }
        }
    }
}
