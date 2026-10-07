package dev.compixel.forge.drawing

import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageAtlas
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.forge.item.NativeGuiRenderTarget
import dev.compixel.forge.render.ScreenFrameRenderer
import dev.compixel.host.NativeImageSource
import dev.compixel.host.ScreenMetrics
import kotlin.math.ceil
import org.jetbrains.skia.Image

/** Independent native viewports share the image scheduler, publication and retirement used by item atlases. */
internal class NativeDrawingRenderer(
    private val backend: ScreenFrameRenderer,
    private val mailbox: NativeImageMailbox<NativeDrawing>,
    private val options: NativeDrawingOptions,
) : NativeImageSource {
    private class Surface(val capture: NativeGuiRenderTarget)

    private val surfaces = ArrayList<Surface?>()
    private var metrics: ScreenMetrics? = null
    private val atlas = NativeImageAtlas(options.cacheCapacity, options.preparationsPerFrame, Pages(), pageCapacity = 1)
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
        override val immediate = true

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
            val surface = surfaces[page] ?: Surface(NativeGuiRenderTarget(backend)).also { surfaces[page] = it }
            surface.capture.draw(
                width,
                height,
                current.renderCoordinate(width.toFloat()),
                current.renderCoordinate(height.toFloat()),
            ) { graphics ->
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
        }

        override fun snapshot(page: Int, buffer: Int, width: Int, height: Int): Image? {
            return backend.copyNativeImage(checkNotNull(surfaces[page]?.capture?.destination), width, height)
        }

        override fun release(image: Image) {
            backend.releaseNativeImage(image)
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
