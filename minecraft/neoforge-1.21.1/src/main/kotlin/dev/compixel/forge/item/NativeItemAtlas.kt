package dev.compixel.forge.item

import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeIconAtlas
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.forge.render.ScreenFrameRenderer
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
 * Draws the shared [NativeIconAtlas] schedule into one native GUI target per page, which keeps the pixels of icons that
 * are not due, and copies a page once after each draw: on the GPU with OpenGL, by readback with the CPU reference
 * renderer. Native model access, preparation and image retirement stay on the render thread.
 */
internal class NativeItemAtlas(
    private val backend: ScreenFrameRenderer,
    private val mailbox: ItemImageMailbox,
    private val options: NativeItemOptions,
) : AutoCloseable {
    private val animations = NativeIconAnimation()
    private val targets = ArrayList<NativeGuiRenderTarget?>()
    private val atlas = NativeIconAtlas(options.cacheCapacity, options.preparationsPerFrame, Pages())
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
                    it.drawnIcons,
                )
            }

    fun recorded(frameGeneration: Long) {
        atlas.recorded(ComposeThread.call { mailbox.activeRequests() })
        generation = frameGeneration
    }

    /** At most one bounded page is prepared per host frame; [density] gives the default image size. */
    fun prepare(now: Long, density: Float): Boolean {
        RenderSystem.assertOnRenderThread()
        val imageSize = options.imageSize ?: NativeIconAtlas.imageSize(density)
        return atlas.prepare(now, NativeIconClock.tick(), Minecraft.getInstance().window.guiScale, imageSize)
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

    private inner class Pages : NativeIconAtlas.Host<ItemIcon> {
        // Both renderers finish the copy before the host frame continues.
        override val immediate = true

        override fun id(icon: ItemIcon) = icon.id

        override fun refresh(icon: ItemIcon) = animations.resolve(icon).scheduled()

        override fun appearance(icon: ItemIcon) = animations.appearance(icon)

        override fun draw(page: Int, buffer: Int, icons: List<NativeIconAtlas.Placement<ItemIcon>>) {
            while (targets.size <= page) targets += null
            val target = targets[page] ?: NativeGuiRenderTarget(backend).also { targets[page] = it }
            val font = Minecraft.getInstance().font
            target.draw(
                atlas.width,
                atlas.height,
                atlas.guiWidth.toFloat(),
                atlas.guiHeight.toFloat(),
                cells = icons.map(atlas::cell),
            ) { graphics ->
                icons.forEach { placement ->
                    val icon = placement.icon
                    if (icon.drawing == null) {
                        // Held by the local player, as in a container slot; compass and clock models need a holder.
                        graphics.renderItem(icon.stack, placement.x, placement.y)
                        graphics.renderItemDecorations(font, icon.stack, placement.x, placement.y)
                    } else {
                        graphics.pose().pushPose()
                        try {
                            graphics.pose().translate(placement.x.toFloat(), placement.y.toFloat(), 0f)
                            icon.drawing.accept(graphics)
                        } finally {
                            graphics.pose().popPose()
                        }
                    }
                }
            }
        }

        override fun snapshot(page: Int, buffer: Int) =
            backend.copyNativeImage(checkNotNull(targets[page]?.destination), atlas.width, atlas.height)

        override fun release(image: Image) = backend.releaseNativeImage(image)

        override fun discard(page: Int) {
            targets.getOrNull(page)?.close()
            if (page < targets.size) targets[page] = null
        }

        override fun publish(regions: Map<Long, NativeImageRegion>, changed: Set<Long>, removed: Set<Long>) {
            mailbox.removeAtlas(removed)
            mailbox.publishAtlas(regions, changed)
        }

        override fun clear() = mailbox.clear()
    }
}
