package dev.compixel.forge.item

import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageAtlas
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.forge.drawing.NativeDrawingClock
import dev.compixel.forge.drawing.scheduled
import dev.compixel.forge.render.ScreenFrameRenderer
import kotlin.math.ceil
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Image

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
 * Draws the shared [NativeImageAtlas] schedule into one native GUI target per page, which keeps the pixels of icons
 * that are not due, and copies a page once after each draw: on the GPU with OpenGL, by readback with the CPU reference
 * renderer. Native model access, preparation and image retirement stay on the render thread.
 */
internal class NativeItemAtlas(
    private val backend: ScreenFrameRenderer,
    private val mailbox: NativeImageMailbox<ItemIcon>,
    private val options: NativeItemOptions,
) : AutoCloseable {
    private val animations = NativeIconAnimation()
    private val targets = ArrayList<NativeGuiRenderTarget?>()
    private val atlas = NativeImageAtlas(options.cacheCapacity, options.preparationsPerFrame, Pages())
    private var generation = 0L

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
        return atlas.prepare(now, NativeDrawingClock.tick(), Minecraft.getInstance().window.guiScale)
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        animations.clear()
        atlas.reset()
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        try {
            atlas.close()
        } finally {
            targets.forEach { it?.close() }
            targets.clear()
        }
    }

    private inner class Pages : NativeImageAtlas.Host<ItemIcon> {
        // Both renderers finish the copy before the host frame continues.
        override val immediate = true

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
            while (targets.size <= page) targets += null
            val target = targets[page] ?: NativeGuiRenderTarget(backend).also { targets[page] = it }
            val font = Minecraft.getInstance().font
            val imageSize = size.width
            val units = 16f / imageSize
            target.draw(width, height, width * units, height * units, cells = icons.map { it.cell }) { graphics ->
                icons.forEach { placement ->
                    val icon = placement.icon
                    graphics.pose().pushPose()
                    try {
                        // The placement starts on a pixel, where the game draws its own items too.
                        graphics.pose().translate(placement.x * units, placement.y * units, 0f)
                        if (icon.drawing == null) {
                            // Held by the local player, as in a container slot; compass and clock models need a holder.
                            graphics.renderItem(icon.stack, 0, 0)
                            graphics.renderItemDecorations(font, icon.stack, 0, 0)
                        } else icon.drawing.accept(graphics)
                    } finally {
                        graphics.pose().popPose()
                    }
                }
            }
        }

        override fun snapshot(page: Int, buffer: Int, width: Int, height: Int) =
            backend.copyNativeImage(checkNotNull(targets[page]?.destination), width, height)

        override fun release(image: Image) = backend.releaseNativeImage(image)

        override fun discard(page: Int) {
            targets.getOrNull(page)?.close()
            if (page < targets.size) targets[page] = null
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
