package dev.composemc.forge.render

import dev.composemc.render.FrameRenderer
import dev.composemc.render.RenderBackend
import dev.composemc.render.UiFrameProfiler
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.jetbrains.skia.Image
import java.util.concurrent.atomic.AtomicLong

internal data class ScreenRenderDestination(val graphics: GuiGraphicsExtractor, val metrics: ScreenMetrics)

internal interface ScreenFrameRenderer : FrameRenderer<ScreenRenderDestination> {
    val profiler: UiFrameProfiler?
    /** GPU copies of native targets for Compose; null where pixels must be read back instead. */
    val nativeSnapshots: NativeSnapshots? get() = null
}

internal interface NativeSnapshots {
    /** Whether native GUI work recorded in this frame is visible to [snapshot] in the same frame. */
    val immediate: Boolean
    /**
     * A Skia-owned GPU copy of the top-left [width]x[height] region of [texture]. Where not
     * [immediate], the texture's contents are consumed: redraw it completely before the next snapshot.
     */
    fun snapshot(texture: GpuTexture, width: Int, height: Int): Image
    fun release(image: Image)
}

internal fun createScreenRenderer(backend: RenderBackend, profiler: UiFrameProfiler? = null): ScreenFrameRenderer = when (backend) {
    RenderBackend.OPENGL -> OpenGlScreenFrameRenderer(profiler)
    RenderBackend.VULKAN -> error("Minecraft 26.1.2 does not provide Vulkan")
    RenderBackend.CPU_RASTER -> CpuScreenFrameRenderer(profiler)
}

internal fun configuredRenderBackend(value: String = System.getProperty("composemc.backend", "auto")): RenderBackend =
    when (value.lowercase(java.util.Locale.ROOT)) {
        // Minecraft 26.1.2 renders only through OpenGL, so auto has a single choice.
        "auto", "opengl" -> RenderBackend.OPENGL
        "cpu", "cpu_raster" -> RenderBackend.CPU_RASTER
        "vulkan" -> error("Minecraft 26.1.2 does not provide Vulkan; use auto, opengl or cpu")
        else -> error("Unknown Compose MC backend '$value'; use auto, opengl or cpu")
    }

/** Reload callbacks publish an epoch; the render thread owns retirement. */
internal object RendererResources {
    private val version = AtomicLong()
    val epoch: Long get() = version.get()
    fun reloaded() { version.incrementAndGet() }
}
