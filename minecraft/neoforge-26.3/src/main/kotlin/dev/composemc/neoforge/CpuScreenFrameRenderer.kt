package dev.composemc.neoforge

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.render.*
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.concurrent.atomic.AtomicLong

/** Explicit CPU/PNG reference presenter, independent of the active Minecraft graphics API. */
internal class CpuScreenFrameRenderer(
    override val profiler: UiFrameProfiler?,
) : ScreenFrameRenderer {
    private var texture: DynamicTexture? = null
    private val textureId = Identifier.fromNamespaceAndPath("composemc", "frame_" + nextTexture.incrementAndGet())
    private var closed = false
    private var frames = 0L
    private var allocations = 0L
    private var uploads = 0L
    private var generation = 0L
    private var hasFrame = false
    override val statistics get() = RendererStatistics(RenderBackend.CPU_RASTER, frames, allocations, uploads,
        generation, if (texture == null) 0 else 1)
    override val needsFrame get() = !hasFrame

    private fun checkOpen() {
        RenderSystem.assertOnRenderThread()
        check(!closed) { "Renderer is closed" }
    }

    override fun render(frame: RecordedFrame) {
        checkOpen()
        val nativeImage = profiler.measureDetail(CpuDetail.IMAGE_IMPORT) {
            NativeImage.read(frame.encodePng())
        }
        val existing = texture
        val existingPixels = existing?.pixels
        if (existing != null && existingPixels != null
            && existingPixels.width == nativeImage.width && existingPixels.height == nativeImage.height) {
            // DynamicTexture assumes ownership of its replacement pixels.
            existing.setPixels(nativeImage)
            existing.upload()
        } else {
            resetTexture()
            val replacement = DynamicTexture({ textureId.toString() }, nativeImage)
            texture = replacement
            allocations++
        }
        uploads++
        generation = frame.generation
        frames++
        hasFrame = true
    }

    override fun present(destination: ScreenRenderDestination) {
        checkOpen()
        if (!hasFrame || texture == null) return
        val graphics = destination.graphics
        val metrics = destination.metrics
        val current = checkNotNull(texture)
        graphics.blit(
            checkNotNull(current.textureView) { "Dynamic texture has no GPU view" },
            checkNotNull(current.sampler) { "Dynamic texture has no sampler" },
            0, 0, metrics.guiWidth, metrics.guiHeight, 0f, 1f, 0f, 1f,
        )
    }

    override fun reset() {
        checkOpen()
        hasFrame = false
        resetTexture()
    }

    private fun resetTexture() {
        val old = texture
        if (old != null) FrameRetirement.afterFrame { old.close() }
        texture = null
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        reset()
        closed = true
    }

    private companion object { val nextTexture = AtomicLong() }
}
