package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import dev.composemc.bridge.NativeIconAtlas
import dev.composemc.bridge.NativeImageRegion
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Image

/**
 * Draws the shared [NativeIconAtlas] schedule into native GUI pages and copies each page to a Skia
 * image on the GPU. Vulkan publishes a completed page on the next frame; OpenGL can publish immediately.
 */
internal class NativeItemAtlas(
    private val mailbox: ItemImageMailbox,
    options: NativeItemOptions,
    private val snapshots: NativeSnapshots,
) : NativeItemPreparer {
    private val atlas = NativeIconAtlas(options.cacheCapacity, options.preparationsPerFrame, options.imageSize, Pages())
    private var capture: NativeGuiCapture? = null
    private var generation = 0L

    override val statistics get() = atlas.statistics.let {
        NativeItemStatistics(it.activeVariants, it.cachedImages, it.pendingImages, it.preparedImages, it.retiredImages,
            generation, it.dynamicVariants, it.animationRefreshes)
    }

    override fun recorded(frameGeneration: Long) {
        atlas.recorded(ComposeThread.call { mailbox.activeRequests() })
        generation = frameGeneration
    }

    override fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        return atlas.prepare(now, NativeIconClock.tick(), Minecraft.getInstance().window.guiScale.toDouble())
    }

    override fun reset() {
        RenderSystem.assertOnRenderThread()
        try { capture?.close() } finally {
            capture = null
            atlas.reset()
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        try { capture?.close() } finally {
            capture = null
            atlas.close()
        }
    }

    private inner class Pages : NativeIconAtlas.Host<ItemIcon> {
        override val immediate get() = snapshots.immediate
        override fun id(icon: ItemIcon) = icon.id
        override fun refresh(icon: ItemIcon) = icon.resolvedRefresh().scheduled()

        override fun draw(buffer: Int, icons: List<NativeIconAtlas.Placement<ItemIcon>>) {
            val target = capture ?: NativeGuiCapture(atlas.width, atlas.height, atlas.buffers).also { capture = it }
            val font = Minecraft.getInstance().font
            target.render(buffer, atlas.guiWidth, atlas.guiHeight) { graphics ->
                icons.forEach { placement ->
                    val icon = placement.icon
                    val drawing = icon.drawing
                    if (drawing == null) {
                        graphics.fakeItem(icon.stack, placement.x, placement.y)
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
        }

        override fun snapshot(buffer: Int) = snapshots.snapshot(checkNotNull(capture).texture(buffer), atlas.width, atlas.height)
        override fun release(image: Image) = snapshots.release(image)
        override fun publish(regions: Map<Long, NativeImageRegion>, removed: Set<Long>) {
            mailbox.removeAtlas(removed)
            mailbox.publishAtlas(regions)
        }
        override fun clear() = mailbox.clear()
    }
}
