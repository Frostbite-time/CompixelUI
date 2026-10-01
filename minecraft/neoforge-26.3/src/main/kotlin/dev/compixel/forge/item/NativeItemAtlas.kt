package dev.compixel.forge.item

import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageAtlas
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.forge.drawing.NativeDrawingClock
import dev.compixel.forge.drawing.scheduled
import dev.compixel.forge.render.FrameRetirement
import dev.compixel.forge.render.NativeSnapshots
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import net.minecraft.client.Minecraft
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

data class NativeItemStatistics(
    val activeVariants: Int = 0,
    val cachedImages: Int = 0,
    val pendingImages: Int = 0,
    val preparedImages: Long = 0,
    val retiredImages: Long = 0,
    val lastRequestGeneration: Long = 0,
    val dynamicVariants: Int = 0,
    val animationRefreshes: Long = 0,
    /** Atlas pages currently holding icons. */
    val pages: Int = 0,
    /** Icons drawn into their cells; a page redraws only its due icons. */
    val drawnIcons: Long = 0,
)

/**
 * Draws the shared [NativeImageAtlas] schedule into native GUI pages, which keep the pixels of icons that are not due.
 * With [snapshots], each page is copied to a Skia image on the GPU: OpenGL copies the page immediately, while Vulkan
 * first copies it into a buffer, because its snapshot consumes the source, and publishes it on the next frame. The CPU
 * reference renderer has no snapshots; it reads each page back and publishes it once the copy arrives.
 */
internal class NativeItemAtlas(
    private val mailbox: NativeImageMailbox<ItemIcon>,
    private val options: NativeItemOptions,
    private val snapshots: NativeSnapshots?,
) : AutoCloseable {
    private class Readback(val request: Long, val pixels: ByteArray, val width: Int, val height: Int)

    private val animations = NativeIconAnimation()
    private val atlas = NativeImageAtlas(options.cacheCapacity, options.preparationsPerFrame, Pages())
    private var capture: NativeGuiCapture? = null
    private var generation = 0L
    // Copies may complete on another thread. At most one page is pending, so any other copy is stale.
    private val readbacks = ConcurrentLinkedQueue<Readback>()
    private var requests = 0L
    private var requested = 0L

    val statistics
        get() =
            atlas.statistics.let {
                NativeItemStatistics(
                    it.activeVariants,
                    it.cachedImages,
                    it.pendingImages,
                    it.preparedImages,
                    it.retiredImages,
                    generation,
                    it.dynamicVariants,
                    it.animationRefreshes,
                    it.pages,
                    it.drawnImages,
                )
            }

    fun recorded(frameGeneration: Long) {
        val requests = ComposeThread.call { mailbox.activeRequests() }
        // A fixed image size draws each icon once; icons shown at other sizes resample that image.
        atlas.recorded(
            options.imageSize?.let { size ->
                requests.map { NativeImageAtlas.Request(it.icon, NativeImageAtlas.Size(size, size)) }
            } ?: requests
        )
        generation = frameGeneration
    }

    /** At most one bounded page is prepared per host frame. */
    fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        return atlas.prepare(now, NativeDrawingClock.tick(), Minecraft.getInstance().window.guiScale.toDouble())
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        requested = 0
        readbacks.clear()
        try {
            atlas.reset()
        } finally {
            capture?.close()
            capture = null
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        requested = 0
        readbacks.clear()
        try {
            atlas.close()
        } finally {
            capture?.close()
            capture = null
        }
    }

    private inner class Pages : NativeImageAtlas.Host<ItemIcon> {
        override val immediate
            get() = snapshots?.immediate ?: false

        override fun layout(size: NativeImageAtlas.Size, capacity: Int): NativeImageAtlas.Layout {
            require(size.width == size.height)
            val gutter = ceil(size.width / 8.0).toInt()
            return NativeImageAtlas.Layout.grid(size, capacity, gutter, gutter)
        }

        override fun id(icon: ItemIcon) = icon.id

        override fun refresh(icon: ItemIcon) = animations.resolve(icon).scheduled()

        override fun appearance(icon: ItemIcon) = animations.appearance(icon)

        override fun draw(
            page: Int,
            buffer: Int,
            size: NativeImageAtlas.Size,
            width: Int,
            height: Int,
            icons: List<NativeImageAtlas.Placement<ItemIcon>>,
        ) {
            val target = capture ?: NativeGuiCapture(width, height, atlas.buffers).also { capture = it }
            // Pages of different image sizes share the capture; each page target keeps its own size.
            target.resize(width, height)
            val font = Minecraft.getInstance().font
            val imageSize = size.width
            target.renderPage(page, icons.map { it.cell }, imageSize) { graphics ->
                icons.forEach { placement ->
                    val icon = placement.icon
                    val drawing = icon.drawing
                    graphics.pose().pushMatrix()
                    try {
                        // The placement starts on a pixel, where the game draws its own items too.
                        graphics.pose().translate(placement.x * 16f / imageSize, placement.y * 16f / imageSize)
                        if (drawing == null) {
                            // Held by the local player, as in a container slot; compass and clock models need a holder.
                            graphics.item(icon.stack, 0, 0)
                            graphics.itemDecorations(font, icon.stack, 0, 0)
                        } else drawing.accept(graphics)
                    } finally {
                        graphics.pose().popMatrix()
                    }
                }
            }
            when {
                snapshots == null -> {
                    val request = ++requests
                    requested = request
                    target.readbackPage(page) { pixels, width, height ->
                        readbacks.add(Readback(request, pixels, width, height))
                    }
                }
                !snapshots.immediate -> target.copyPage(page, buffer)
            }
        }

        override fun snapshot(page: Int, buffer: Int, width: Int, height: Int): Image? {
            val target = checkNotNull(capture)
            if (snapshots != null) {
                val texture = if (snapshots.immediate) target.pageTexture(page) else target.texture(buffer)
                return snapshots.snapshot(texture, width, height)
            }
            while (true) {
                val copy = readbacks.poll() ?: return null
                if (copy.request == requested)
                    return Image.makeRaster(
                        ImageInfo(copy.width, copy.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                        copy.pixels,
                        copy.width * 4,
                    )
            }
        }

        override fun release(image: Image) {
            if (snapshots != null) snapshots.release(image) else FrameRetirement.afterFrame { image.close() }
        }

        override fun discard(page: Int) {
            capture?.discardPage(page)
        }

        override fun publish(
            regions: Map<NativeImageAtlas.Variant, NativeImageRegion>,
            changed: Set<NativeImageAtlas.Variant>,
            removed: Set<NativeImageAtlas.Variant>,
        ) {
            mailbox.publish(regions, changed, removed)
        }

        override fun clear() = mailbox.clear()
    }
}
