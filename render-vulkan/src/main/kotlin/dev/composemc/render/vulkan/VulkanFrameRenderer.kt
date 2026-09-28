package dev.composemc.render.vulkan

import dev.composemc.render.RecordedFrame
import dev.composemc.render.RenderBackend
import dev.composemc.render.RendererStatistics
import dev.composemc.render.RetiredImages
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.lwjgl.vulkan.VK12

/**
 * Draws into borrowed Vulkan images on the host's graphics queue. The adapter supplies the target and surrounds render
 * with its queue/layout handoff. Context and temporary Skia wrappers are owned here; native host handles are not.
 */
@OptIn(ExperimentalSkikoApi::class)
class VulkanFrameRenderer(handles: VulkanDeviceHandles) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val context =
        DirectContext.makeVulkan(
            handles.instance,
            handles.physicalDevice,
            handles.device,
            handles.graphicsQueue,
            handles.graphicsQueueFamily,
            handles.getInstanceProcAddress,
            handles.getDeviceProcAddress,
            handles.apiVersion,
            null,
        )
    private var closed = false
    private var frames = 0L
    private var generation = 0L
    private var snapshots = 0L
    private val snapshotImages = mutableSetOf<Image>()
    private val retiredImages = RetiredImages()
    private var strandedImages = 0
    var needsFrame = true
        private set

    // The adapter adds its retained target's allocation/liveness counters.
    val statistics
        get() =
            RendererStatistics(
                RenderBackend.VULKAN,
                frames,
                0,
                0,
                generation,
                nativeImageCopies = snapshots,
                liveNativeImages = snapshotImages.size + retiredImages.size,
                retiredNativeImages = retiredImages.size,
                strandedNativeImages = strandedImages,
            )

    private fun checkOpen() {
        check(Thread.currentThread() === owner) { "Vulkan renderer accessed outside its owning thread" }
        check(!closed) { "Vulkan renderer is closed" }
    }

    fun render(frame: RecordedFrame, target: VulkanImageTarget) {
        checkOpen()
        require(frame.viewport.width == target.width && frame.viewport.height == target.height)
        BackendRenderTarget.makeVulkan(
                target.width,
                target.height,
                target.image,
                VK12.VK_IMAGE_TILING_OPTIMAL,
                VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                target.format,
                target.usage,
                VK12.VK_SAMPLE_COUNT_1_BIT,
                1,
            )
            .use { backend ->
                checkNotNull(
                        Surface.makeFromBackendRenderTarget(
                            context,
                            backend,
                            SurfaceOrigin.TOP_LEFT,
                            SurfaceColorFormat.RGBA_8888,
                            null,
                        )
                    ) {
                        "Skia rejected the borrowed Vulkan UI image"
                    }
                    .use { surface ->
                        surface.canvas.clear(0)
                        frame.draw(surface.canvas)
                        context.flushAndSubmit(surface, false)
                    }
            }
        frames++
        generation = frame.generation
        needsFrame = false
        // Replaced frames and display lists may have held the last other references to retired images.
        retiredImages.release()
    }

    /**
     * Copies the top-left [width]x[height] region of a host color image into a Skia-owned image on the GPU. The host
     * image is wrapped only for the copy, which is submitted before this returns. [layout] is its layout on entry;
     * afterwards the host must re-establish its own layout before writing again (see
     * [VulkanImageBarriers.releaseAfterSnapshot]). [bottomUp] marks images whose first memory row is the bottom of the
     * picture.
     */
    fun snapshotImage(source: VulkanImageTarget, layout: Int, width: Int, height: Int, bottomUp: Boolean): Image {
        checkOpen()
        require(width in 1..source.width && height in 1..source.height)
        val image =
            BackendRenderTarget.makeVulkan(
                    source.width,
                    source.height,
                    source.image,
                    VK12.VK_IMAGE_TILING_OPTIMAL,
                    layout,
                    source.format,
                    source.usage,
                    VK12.VK_SAMPLE_COUNT_1_BIT,
                    1,
                )
                .use { backend ->
                    checkNotNull(
                            Surface.makeFromBackendRenderTarget(
                                context,
                                backend,
                                if (bottomUp) SurfaceOrigin.BOTTOM_LEFT else SurfaceOrigin.TOP_LEFT,
                                SurfaceColorFormat.RGBA_8888,
                                null,
                            )
                        ) {
                            "Skia rejected the borrowed Vulkan image"
                        }
                        .use { surface ->
                            // A wrapped render target is not a texture, so the snapshot is a GPU copy.
                            checkNotNull(surface.makeImageSnapshot(IRect.makeWH(width, height))) {
                                "Skia could not copy the Vulkan image"
                            }
                        }
                }
        context.flush()
        context.submit(false)
        image.imageInfo
        snapshotImages += image
        snapshots++
        return image
    }

    /**
     * Retires a [snapshotImage] result. Recorded pictures retain their own references, and Compose may drop those on
     * its own thread, so the image stays open until this renderer holds its last reference.
     */
    fun releaseImage(image: Image) {
        checkOpen()
        if (snapshotImages.remove(image)) retiredImages.retire(image)
        retiredImages.release()
    }

    fun reset() {
        checkOpen()
        needsFrame = true
    }

    /** The host must retire this context after all queued work referencing it has finished. */
    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        snapshotImages.forEach(retiredImages::retire)
        snapshotImages.clear()
        strandedImages += retiredImages.releaseAll()
        context.close()
        closed = true
    }
}
