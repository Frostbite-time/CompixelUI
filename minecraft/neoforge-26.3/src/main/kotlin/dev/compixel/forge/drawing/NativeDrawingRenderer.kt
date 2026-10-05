package dev.compixel.forge.drawing

import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageAtlas
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.forge.item.NativeGuiCapture
import dev.compixel.forge.render.FrameRetirement
import dev.compixel.forge.render.NativeSnapshots
import dev.compixel.host.NativeImageSource
import dev.compixel.host.ScreenMetrics
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Independent native viewports share the image scheduler, publication and retirement used by item atlases. */
internal class NativeDrawingRenderer(
    private val mailbox: NativeImageMailbox<NativeDrawing>,
    private val options: NativeDrawingOptions,
    private val snapshots: NativeSnapshots?,
) : NativeImageSource {
    private class Surface(val capture: NativeGuiCapture) {
        val completed = ConcurrentLinkedQueue<ByteArray>()
    }

    private val surfaces = ArrayList<Surface?>()
    private var metrics: ScreenMetrics? = null
    private val atlas =
        NativeImageAtlas(options.cacheCapacity, options.preparationsPerFrame, Pages(), options.preparationsPerFrame)
    val statistics
        get() = atlas.statistics

    override fun recorded(frameGeneration: Long) {
        atlas.recorded(ComposeThread.call { mailbox.activeRequests() })
    }

    override fun prepare(now: Long, current: ScreenMetrics): Boolean {
        RenderSystem.assertOnRenderThread()
        metrics = current
        return atlas.prepare(now, NativeDrawingClock.tick(), current.guiScale.toDouble())
    }

    override fun reset() {
        RenderSystem.assertOnRenderThread()
        atlas.reset()
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        atlas.close()
    }

    private inner class Pages : NativeImageAtlas.Host<NativeDrawing> {
        override val immediate = snapshots?.immediate ?: false

        override fun id(icon: NativeDrawing) = icon.id

        override fun refresh(icon: NativeDrawing) =
            (if (icon.refresh === NativeRefresh.AUTO) NativeRefresh.GAME_TICK else icon.refresh).scheduled()

        override fun layout(size: NativeImageAtlas.Size, capacity: Int) = NativeImageAtlas.Layout.single(size)

        override fun draw(
            page: Int,
            buffer: Int,
            size: NativeImageAtlas.Size,
            width: Int,
            height: Int,
            icons: List<NativeImageAtlas.Placement<NativeDrawing>>,
        ) {
            val current = checkNotNull(metrics)
            val content = icons.single().icon
            while (surfaces.size <= page) surfaces += null
            val surface =
                surfaces[page] ?: Surface(NativeGuiCapture(width, height, atlas.buffers)).also { surfaces[page] = it }
            surface.capture.renderLocal(buffer, current.guiScale) { graphics ->
                content.drawing.accept(
                    NativeDrawingContext(
                        graphics,
                        ceil(current.renderCoordinate(width.toFloat())).toInt(),
                        ceil(current.renderCoordinate(height.toFloat())).toInt(),
                        width,
                        height,
                        current.guiScale,
                    )
                )
            }
            if (snapshots == null) surface.capture.readback(buffer) { pixels, _, _ -> surface.completed.add(pixels) }
        }

        override fun snapshot(page: Int, buffer: Int, width: Int, height: Int): Image? {
            val surface = checkNotNull(surfaces[page])
            return if (snapshots != null) snapshots.snapshot(surface.capture.texture(buffer), width, height)
            else
                surface.completed.poll()?.let { pixels ->
                    Image.makeRaster(
                        ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                        pixels,
                        width * 4,
                    )
                }
        }

        override fun release(image: Image) {
            if (snapshots != null) snapshots.release(image) else FrameRetirement.afterFrame { image.close() }
        }

        override fun discard(page: Int) {
            surfaces.getOrNull(page)?.capture?.close()
            if (page < surfaces.size) surfaces[page] = null
        }

        override fun publish(
            regions: Map<NativeImageAtlas.Variant, NativeImageRegion>,
            changed: Set<NativeImageAtlas.Variant>,
            removed: Set<NativeImageAtlas.Variant>,
        ) = mailbox.publish(regions, changed, removed)

        override fun clear() = mailbox.clear()
    }
}
