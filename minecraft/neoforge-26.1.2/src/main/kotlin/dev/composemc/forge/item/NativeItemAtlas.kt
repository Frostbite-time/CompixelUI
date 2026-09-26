package dev.composemc.forge.item

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import dev.composemc.bridge.NativeIconAtlas
import dev.composemc.bridge.NativeImageRegion
import net.minecraft.client.Minecraft
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentLinkedQueue
import dev.composemc.forge.render.FrameRetirement
import dev.composemc.forge.render.NativeSnapshots

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
 * Draws the shared [NativeIconAtlas] schedule into native GUI pages. With [snapshots], each page is copied
 * to a Skia image on the GPU: Vulkan publishes it on the next frame, OpenGL immediately. The CPU reference
 * renderer has no snapshots; it reads each page back and publishes it once the copy arrives.
 */
internal class NativeItemAtlas(
    private val mailbox: ItemImageMailbox,
    options: NativeItemOptions,
    private val snapshots: NativeSnapshots?,
) : AutoCloseable {
    private class Readback(val request: Long, val pixels: ByteArray, val width: Int, val height: Int)
    private val animations = NativeIconAnimation()
    private val atlas = NativeIconAtlas(options.cacheCapacity, options.preparationsPerFrame, options.imageSize, Pages())
    private var capture: NativeGuiCapture? = null
    private var generation = 0L
    // Copies may complete on another thread. At most one page is pending, so any other copy is stale.
    private val readbacks = ConcurrentLinkedQueue<Readback>()
    private var requests = 0L
    private var requested = 0L

    val statistics get() = atlas.statistics.let {
        NativeItemStatistics(it.activeVariants, it.cachedImages, it.pendingImages, it.preparedImages, it.retiredImages,
            generation, it.dynamicVariants, it.animationRefreshes)
    }

    fun recorded(frameGeneration: Long) {
        atlas.recorded(ComposeThread.call { mailbox.activeRequests() })
        generation = frameGeneration
    }

    /** At most one bounded page is prepared per host frame. */
    fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        return atlas.prepare(now, NativeIconClock.tick(), Minecraft.getInstance().window.guiScale.toDouble())
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        requested = 0
        readbacks.clear()
        try { capture?.close() } finally {
            capture = null
            atlas.reset()
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        requested = 0
        readbacks.clear()
        try { capture?.close() } finally {
            capture = null
            atlas.close()
        }
    }

    private inner class Pages : NativeIconAtlas.Host<ItemIcon> {
        override val immediate get() = snapshots?.immediate ?: false
        override fun id(icon: ItemIcon) = icon.id
        override fun refresh(icon: ItemIcon) = animations.resolve(icon).scheduled()
        override fun appearance(icon: ItemIcon) = animations.appearance(icon)

        override fun draw(buffer: Int, icons: List<NativeIconAtlas.Placement<ItemIcon>>) {
            val target = capture ?: NativeGuiCapture(atlas.width, atlas.height, atlas.buffers).also { capture = it }
            val font = Minecraft.getInstance().font
            target.render(buffer, atlas.guiWidth, atlas.guiHeight) { graphics ->
                icons.forEach { placement ->
                    val icon = placement.icon
                    val drawing = icon.drawing
                    if (drawing == null) {
                        // Held by the local player, as in a container slot; compass and clock models need a holder.
                        graphics.item(icon.stack, placement.x, placement.y)
                        graphics.itemDecorations(font, icon.stack, placement.x, placement.y)
                    } else {
                        graphics.pose().pushMatrix()
                        try {
                            graphics.pose().translate(placement.x.toFloat(), placement.y.toFloat())
                            drawing.accept(graphics)
                        } finally { graphics.pose().popMatrix() }
                    }
                }
                true
            }
            if (snapshots == null) {
                val request = ++requests
                requested = request
                target.readback(buffer) { pixels, width, height -> readbacks.add(Readback(request, pixels, width, height)) }
            }
        }

        override fun snapshot(buffer: Int): Image? {
            if (snapshots != null) return snapshots.snapshot(checkNotNull(capture).texture(buffer), atlas.width, atlas.height)
            while (true) {
                val copy = readbacks.poll() ?: return null
                if (copy.request == requested) return Image.makeRaster(
                    ImageInfo(copy.width, copy.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), copy.pixels, copy.width * 4)
            }
        }
        override fun release(image: Image) {
            if (snapshots != null) snapshots.release(image) else FrameRetirement.afterFrame { image.close() }
        }
        override fun publish(regions: Map<Long, NativeImageRegion>, removed: Set<Long>) {
            mailbox.removeAtlas(removed)
            mailbox.publishAtlas(regions)
        }
        override fun clear() = mailbox.clear()
    }
}
