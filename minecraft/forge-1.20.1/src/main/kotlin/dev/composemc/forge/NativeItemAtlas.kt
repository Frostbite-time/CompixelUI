package dev.composemc.forge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import dev.composemc.bridge.NativeIconAtlas
import dev.composemc.bridge.NativeImageRegion
import dev.composemc.render.gl.OpenGlDestination
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
)

/**
 * Draws the shared [NativeIconAtlas] schedule into one native GUI page per host frame and copies that
 * page once: on the GPU with OpenGL, by readback with the CPU reference renderer. Native model access,
 * preparation and image retirement stay on the render thread.
 */
internal class NativeItemAtlas(
    private val backend: ScreenFrameRenderer,
    private val mailbox: ItemImageMailbox,
    options: NativeItemOptions,
) : AutoCloseable {
    private val animations = NativeIconAnimation()
    private val target = NativeGuiRenderTarget(backend)
    private val atlas = NativeIconAtlas(options.cacheCapacity, options.preparationsPerFrame, options.imageSize, Pages())
    private var page: OpenGlDestination? = null
    private var generation = 0L

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
        return atlas.prepare(now, NativeIconClock.tick(), Minecraft.getInstance().window.guiScale)
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        animations.clear()
        atlas.reset()
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        try { atlas.close() } finally {
            page = null
            target.close()
        }
    }

    private inner class Pages : NativeIconAtlas.Host<ItemIcon> {
        // Both renderers finish the copy before the host frame continues.
        override val immediate = true
        override fun id(icon: ItemIcon) = icon.id
        override fun refresh(icon: ItemIcon) = animations.resolve(icon).scheduled()
        override fun appearance(icon: ItemIcon) = animations.appearance(icon)

        override fun draw(buffer: Int, icons: List<NativeIconAtlas.Placement<ItemIcon>>) {
            val font = Minecraft.getInstance().font
            page = target.draw(atlas.width, atlas.height, atlas.guiWidth.toFloat(), atlas.guiHeight.toFloat()) { graphics ->
                icons.forEach { placement ->
                    val icon = placement.icon
                    if (icon.drawing == null) {
                        graphics.renderFakeItem(icon.stack, placement.x, placement.y)
                        graphics.renderItemDecorations(font, icon.stack, placement.x, placement.y)
                    } else {
                        graphics.pose().pushPose()
                        try {
                            graphics.pose().translate(placement.x.toFloat(), placement.y.toFloat(), 0f)
                            icon.drawing.accept(graphics)
                        } finally { graphics.pose().popPose() }
                    }
                }
            }.first
        }

        override fun snapshot(buffer: Int) = backend.copyNativeImage(checkNotNull(page), atlas.width, atlas.height)
        override fun release(image: Image) = backend.releaseNativeImage(image)
        override fun publish(regions: Map<Long, NativeImageRegion>, removed: Set<Long>) {
            mailbox.removeAtlas(removed)
            mailbox.publishAtlas(regions)
        }
        override fun clear() = mailbox.clear()
    }
}
