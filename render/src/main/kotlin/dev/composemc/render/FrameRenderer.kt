package dev.composemc.render

enum class RenderBackend { CPU_RASTER, OPENGL, VULKAN }

data class RendererStatistics(
    val backend: RenderBackend,
    val renderedFrames: Long,
    val surfaceAllocations: Long,
    val fullFrameUploads: Long,
    val lastFrameGeneration: Long = 0,
    val liveSurfaces: Int = 0,
    val gpuRender: TimingSummary? = null,
    val gpuPresent: TimingSummary? = null,
    val nativeImageCopies: Long = 0,
    val nativeImageReadbacks: Long = 0,
    val liveNativeImages: Int = 0,
)

/**
 * Backend-owned retained output. T is the version adapter's presentation destination, not a
 * universal texture handle. No GL/Vulkan handles or synchronization primitives cross this SPI.
 *
 * Calls run on the backend's owning thread, at phases permitted by the version adapter.
 * render borrows a picture only for that call; deferred backends must retain their own recording.
 * present may reuse the last output many times. The backend owns submission ordering and retirement
 * of every resource used by render/present, including GPU work that outlives either call.
 */
interface FrameRenderer<in T> : AutoCloseable {
    val statistics: RendererStatistics
    val needsFrame: Boolean
    fun render(frame: RecordedFrame)
    fun present(destination: T)
    /** Discard retained output, safely retire resources, and request a fresh frame. */
    fun reset()
    /** Idempotent on the owning thread. May not free resources still used by queued GPU work. */
    override fun close()
}
