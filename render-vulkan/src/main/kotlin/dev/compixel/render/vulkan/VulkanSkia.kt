package dev.compixel.render.vulkan

import dev.compixel.render.RetiredImages
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.ExperimentalSkikoApi

/**
 * Skia on one Vulkan device, shared by every [VulkanFrameRenderer] that draws with it: screens, HUD layers and warm-up
 * use one Skia context, so its pipelines, glyph atlases and resource cache serve them all, and a layer that opens later
 * finds them ready. A renderer keeps its own images. When it closes, the images that recorded pictures still reference
 * wait here until nothing references them, and are released by the next renderer that draws or by [collect].
 *
 * It lives until [close], which the host calls before it destroys the device, after the last submission that used it
 * has completed. Use it on the thread that owns the device's graphics queue.
 */
@OptIn(ExperimentalSkikoApi::class)
class VulkanSkia(private val handles: VulkanDeviceHandles) : AutoCloseable {
    private val owner = Thread.currentThread()
    internal val context: DirectContext =
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
            .also { live.incrementAndGet() }
    private val retired = RetiredImages()
    private var closed = false

    /** Renderers that are open on this device. */
    var renderers = 0
        private set

    /** Images of closed renderers that recorded pictures still reference. */
    val retiredImages
        get() = retired.size

    internal fun checkOpen() {
        check(Thread.currentThread() === owner) { "Vulkan renderer accessed outside its owning thread" }
        check(!closed) { "The device's Skia context is closed" }
    }

    internal fun attach() {
        checkOpen()
        renderers++
    }

    /** A renderer closed: what it could not release yet waits here. Returns how many images that is. */
    internal fun detach(images: RetiredImages): Int {
        renderers--
        val waiting = images.moveTo(retired)
        retired.release()
        return waiting
    }

    internal fun releaseRetired() = retired.release()

    /**
     * Releases the images of closed renderers that nothing references any more. Renderers do this as they draw; a host
     * calls it now and then while none is open, for example each tick.
     */
    fun collect() {
        check(Thread.currentThread() === owner) { "Vulkan renderer accessed outside its owning thread" }
        if (!closed) retired.release()
    }

    /**
     * Closes Skia's context and what it cached, once every renderer has closed and the device has finished the work
     * they submitted. Returns how many images recorded pictures still referenced: their final release then happens
     * elsewhere, so close the UI before this.
     */
    fun shutDown(): Int {
        check(Thread.currentThread() === owner) { "Vulkan renderer accessed outside its owning thread" }
        if (closed) return 0
        check(renderers == 0) { "$renderers renderers still draw with this Skia context" }
        val stranded = retired.releaseAll()
        context.close()
        live.decrementAndGet()
        closed = true
        synchronized(shared) { shared.remove(handles.device, this) }
        return stranded
    }

    override fun close() {
        shutDown()
    }

    companion object {
        private val shared = HashMap<Long, VulkanSkia>()
        private val live = AtomicInteger()

        /** The Skia context every renderer of this device shares, created on first use. */
        fun shared(handles: VulkanDeviceHandles): VulkanSkia =
            synchronized(shared) { shared.getOrPut(handles.device) { VulkanSkia(handles) } }

        /** The device's shared Skia context when one exists; [device] is its VkDevice handle. */
        fun sharedOrNull(device: Long): VulkanSkia? = synchronized(shared) { shared[device] }

        /** Every device's shared Skia context, for a host that shuts down. */
        fun sharedContexts(): List<VulkanSkia> = synchronized(shared) { shared.values.toList() }

        /** Skia contexts that exist now, over every device: one per device that has drawn UI. */
        val liveContexts
            get() = live.get()
    }
}
