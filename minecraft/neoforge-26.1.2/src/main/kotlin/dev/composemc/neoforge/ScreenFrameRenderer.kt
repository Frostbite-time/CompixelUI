package dev.composemc.neoforge

import dev.composemc.render.FrameRenderer
import dev.composemc.render.RenderBackend
import dev.composemc.render.UiFrameProfiler
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.concurrent.atomic.AtomicLong

internal data class ScreenRenderDestination(val graphics: GuiGraphicsExtractor, val metrics: ScreenMetrics)

internal interface ScreenFrameRenderer : FrameRenderer<ScreenRenderDestination> {
    val profiler: UiFrameProfiler?
}

internal fun createScreenRenderer(backend: RenderBackend, profiler: UiFrameProfiler? = null): ScreenFrameRenderer = when (backend) {
    RenderBackend.OPENGL -> OpenGlScreenFrameRenderer(profiler)
    RenderBackend.VULKAN -> error("Minecraft 26.1.2 does not provide Vulkan")
    RenderBackend.CPU_RASTER -> CpuScreenFrameRenderer(profiler)
}

internal fun configuredRenderBackend(value: String = System.getProperty("composemc.backend", "auto")): RenderBackend =
    when (value.lowercase(java.util.Locale.ROOT)) {
        "auto" -> RenderBackend.OPENGL
        "opengl" -> RenderBackend.OPENGL
        "vulkan" -> RenderBackend.VULKAN
        "cpu", "cpu_raster" -> RenderBackend.CPU_RASTER
        else -> error("Unknown Compose MC backend '$value'; use auto, opengl, vulkan or cpu")
    }

/** Reload callbacks publish an epoch; the render thread owns retirement. */
internal object RendererResources {
    private val version = AtomicLong()
    val epoch: Long get() = version.get()
    fun reloaded() { version.incrementAndGet() }
}
