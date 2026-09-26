package dev.composemc.forge.render

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.render.*
import dev.composemc.render.gl.OpenGlDestination
import dev.composemc.render.gl.OpenGlFrameRenderer
import dev.composemc.render.gl.readFramebufferImage
import org.jetbrains.skia.Image
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL33C.*
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

internal data class ScreenRenderDestination(val graphics: GuiGraphics, val metrics: ScreenMetrics)

internal interface ScreenFrameRenderer : FrameRenderer<ScreenRenderDestination> {
    val profiler: UiFrameProfiler?
    fun <T> nativeGpu(phase: GpuPhase, action: () -> T): T = action()
    fun copyNativeImage(source: OpenGlDestination, width: Int = source.width, height: Int = source.height): Image
    fun releaseNativeImage(image: Image) = releaseNativeImages(listOf(image))
    fun releaseNativeImages(images: List<Image>)
}

internal fun createScreenRenderer(backend: RenderBackend, profiler: UiFrameProfiler? = null): ScreenFrameRenderer = when (backend) {
    RenderBackend.OPENGL -> GlScreenFrameRenderer(profiler)
    RenderBackend.CPU_RASTER -> CpuScreenFrameRenderer(profiler)
    RenderBackend.VULKAN -> error("NeoForge 1.21.1 does not provide a Vulkan renderer")
}

/** Keeps GL destination handles inside the version adapter and the GL backend. */
private class GlScreenFrameRenderer(override val profiler: UiFrameProfiler?) : ScreenFrameRenderer {
    private val renderer = OpenGlFrameRenderer(measureGpu = java.lang.Boolean.getBoolean("composemc.diagnostics"), profiler = profiler)
    override fun <T> nativeGpu(phase: GpuPhase, action: () -> T): T = renderer.profileGpu(phase, action)
    override val statistics get() = renderer.statistics
    override val needsFrame get() = renderer.needsFrame
    override fun render(frame: RecordedFrame) = renderer.render(frame)
    override fun present(destination: ScreenRenderDestination) {
        RenderSystem.assertOnRenderThread()
        val metrics = destination.metrics
        renderer.present(OpenGlDestination(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
            metrics.framebufferWidth, metrics.framebufferHeight))
    }
    override fun reset() = renderer.reset()
    override fun copyNativeImage(source: OpenGlDestination, width: Int, height: Int) =
        profiler.measureDetail(CpuDetail.IMAGE_IMPORT) { renderer.copyFramebuffer(source, width, height) }
    override fun releaseNativeImages(images: List<Image>) = profiler.measureDetail(CpuDetail.IMAGE_RETIRE) { renderer.releaseImages(images) }
    override fun close() = renderer.close()
}

/** Explicit reference backend for comparison and diagnostics. */
private class CpuScreenFrameRenderer(override val profiler: UiFrameProfiler?) : ScreenFrameRenderer {
    private var texture: DynamicTexture? = null
    private val textureId = ResourceLocation.fromNamespaceAndPath("composemc", "frame_" + nextTexture.incrementAndGet())
    private var closed = false
    private var frames = 0L
    private var allocations = 0L
    private var generation = 0L
    private var imageReadbacks = 0L
    private val importedImages = mutableSetOf<Image>()
    override val statistics get() = RendererStatistics(RenderBackend.CPU_RASTER, frames, allocations, frames,
        generation, if (texture == null) 0 else 1,
        nativeImageReadbacks = imageReadbacks, liveNativeImages = importedImages.size)
    override val needsFrame get() = texture == null

    private fun checkOpen() {
        RenderSystem.assertOnRenderThread()
        check(!closed) { "Renderer is closed" }
    }

    override fun render(frame: RecordedFrame) {
        checkOpen()
        val image = NativeImage.read(ByteArrayInputStream(frame.encodePng()))
        val existing = texture
        val pixels = existing?.pixels
        if (existing != null && pixels != null && pixels.width == image.width && pixels.height == image.height) {
            existing.setPixels(image)
            existing.upload()
        } else {
            reset()
            val replacement = try { DynamicTexture(image) } catch (error: Throwable) { image.close(); throw error }
            try { Minecraft.getInstance().textureManager.register(textureId, replacement) }
            catch (error: Throwable) { replacement.close(); throw error }
            texture = replacement
            allocations++
        }
        generation = frame.generation
        frames++
    }

    override fun present(destination: ScreenRenderDestination) {
        checkOpen()
        if (texture == null) return
        val graphics = destination.graphics
        val metrics = destination.metrics
        val blending = glIsEnabled(GL_BLEND)
        val blend = intArrayOf(glGetInteger(GL_BLEND_SRC_RGB), glGetInteger(GL_BLEND_DST_RGB),
            glGetInteger(GL_BLEND_SRC_ALPHA), glGetInteger(GL_BLEND_DST_ALPHA))
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        try {
            graphics.blit(textureId, 0, 0, metrics.guiWidth, metrics.guiHeight, 0f, 0f,
                metrics.framebufferWidth, metrics.framebufferHeight, metrics.framebufferWidth, metrics.framebufferHeight)
            graphics.flush()
        } finally {
            RenderSystem.blendFuncSeparate(blend[0], blend[1], blend[2], blend[3])
            if (!blending) RenderSystem.disableBlend()
        }
    }

    override fun reset() {
        checkOpen()
        if (texture != null) Minecraft.getInstance().textureManager.release(textureId)
        texture = null
    }

    override fun copyNativeImage(source: OpenGlDestination, width: Int, height: Int): Image {
        checkOpen()
        return profiler.measureDetail(CpuDetail.IMAGE_IMPORT) {
            readFramebufferImage(source, width, height).also { importedImages += it; imageReadbacks++ }
        }
    }

    override fun releaseNativeImages(images: List<Image>) {
        checkOpen()
        profiler.measureDetail(CpuDetail.IMAGE_RETIRE) {
            images.forEach { image -> if (importedImages.remove(image)) image.close() }
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        reset()
        importedImages.forEach(Image::close)
        importedImages.clear()
        closed = true
    }

    private companion object { val nextTexture = AtomicLong() }
}

internal fun configuredRenderBackend(value: String = System.getProperty("composemc.backend", "auto")): RenderBackend =
    when (value.lowercase(java.util.Locale.ROOT)) {
        // Minecraft 1.21.1 renders only through OpenGL, so auto has a single choice.
        "auto", "opengl" -> RenderBackend.OPENGL
        "cpu", "cpu_raster" -> RenderBackend.CPU_RASTER
        "vulkan" -> error("NeoForge 1.21.1 does not provide a Vulkan renderer; use auto, opengl or cpu")
        else -> error("Unknown Compose MC backend '$value'; use auto, opengl or cpu")
    }

/** Reload callbacks only publish an epoch; the next Screen render owns GPU retirement. */
internal object RendererResources {
    private val version = AtomicLong()
    val epoch: Long get() = version.get()
    fun reloaded() { version.incrementAndGet() }
}
