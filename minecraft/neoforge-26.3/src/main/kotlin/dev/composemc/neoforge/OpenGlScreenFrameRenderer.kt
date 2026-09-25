package dev.composemc.neoforge

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.opengl.GlTexture
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.composemc.render.*
import dev.composemc.render.gl.OpenGlFrameRenderer
import org.jetbrains.skia.Image

internal class OpenGlScreenFrameRenderer(override val profiler: UiFrameProfiler?) : ScreenFrameRenderer {
    private val renderer = OpenGlFrameRenderer(profiler = profiler)
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var sampler: GpuSampler? = null
    private var closed = false
    override val statistics get() = renderer.statistics
    override val needsFrame get() = renderer.needsFrame

    // Minecraft issues its GL commands immediately in this same context.
    override val nativeSnapshots = object : NativeSnapshots {
        override val immediate = true
        override fun snapshot(texture: GpuTexture, width: Int, height: Int): Image =
            renderer.copyTexture((texture as GlTexture).glId(), texture.getWidth(0), texture.getHeight(0), width, height)
        override fun release(image: Image) = renderer.releaseImage(image)
    }

    override fun render(frame: RecordedFrame) {
        RenderSystem.assertOnRenderThread()
        val width = frame.viewport.width
        val height = frame.viewport.height
        if (texture?.getWidth(0) != width || texture?.getHeight(0) != height) {
            releaseTarget()
            val device = RenderSystem.getDevice()
            texture = device.createTexture({ "composemc-gl-frame" },
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1)
            view = device.createTextureView(texture!!)
            sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty())
        }
        renderer.render(frame)
        renderer.copyToTexture((texture as GlTexture).glId(), width, height)
    }

    override fun present(destination: ScreenRenderDestination) {
        // Minecraft composites the copied frame, so OpenGlFrameRenderer.present never polls GPU timings.
        renderer.collectGpuTimings()
        val output = view ?: return
        presentPremultiplied(destination, output, sampler!!, flipY = true)
    }

    private fun releaseTarget() {
        val oldView = view; val oldSampler = sampler; val oldTexture = texture
        view = null; sampler = null; texture = null
        if (oldTexture != null) FrameRetirement.afterFrame {
            oldView?.close(); oldSampler?.close(); oldTexture.close()
        }
    }
    override fun reset() { releaseTarget(); renderer.reset() }
    override fun close() {
        if (closed) return
        releaseTarget()
        renderer.close()
        closed = true
    }
}
