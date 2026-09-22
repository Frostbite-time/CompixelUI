package dev.composemc.render.vulkan

import dev.composemc.render.RecordedFrame
import dev.composemc.render.RenderBackend
import dev.composemc.render.RendererStatistics
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.lwjgl.vulkan.VK12

/**
 * Draws into borrowed Vulkan images on the host's graphics queue. The adapter
 * supplies the target and surrounds render with its queue/layout handoff.
 * Context and temporary Skia wrappers are owned here; native host handles are not.
 */
@OptIn(ExperimentalSkikoApi::class)
class VulkanFrameRenderer(handles: VulkanDeviceHandles) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val context = DirectContext.makeVulkan(
        handles.instance, handles.physicalDevice, handles.device, handles.graphicsQueue,
        handles.graphicsQueueFamily, handles.getInstanceProcAddress, handles.getDeviceProcAddress,
        handles.apiVersion, null,
    )
    private var closed = false
    private var frames = 0L
    private var generation = 0L
    var needsFrame = true
        private set

    // The adapter adds its retained target's allocation/liveness counters.
    val statistics get() = RendererStatistics(RenderBackend.VULKAN, frames, 0, 0, generation)

    private fun checkOpen() {
        check(Thread.currentThread() === owner) { "Vulkan renderer accessed outside its owning thread" }
        check(!closed) { "Vulkan renderer is closed" }
    }

    fun render(frame: RecordedFrame, target: VulkanImageTarget) {
        checkOpen()
        require(frame.viewport.width == target.width && frame.viewport.height == target.height)
        BackendRenderTarget.makeVulkan(target.width, target.height, target.image,
            VK12.VK_IMAGE_TILING_OPTIMAL, VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            target.format, target.usage, VK12.VK_SAMPLE_COUNT_1_BIT, 1).use { backend ->
            checkNotNull(Surface.makeFromBackendRenderTarget(context, backend, SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888, null)) { "Skia rejected the borrowed Vulkan UI image" }.use { surface ->
                surface.canvas.clear(0)
                frame.draw(surface.canvas)
                context.flushAndSubmit(surface, false)
            }
        }
        frames++
        generation = frame.generation
        needsFrame = false
    }

    fun reset() { checkOpen(); needsFrame = true }

    /** The host must retire this context after all queued work referencing it has finished. */
    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        context.close()
        closed = true
    }
}
