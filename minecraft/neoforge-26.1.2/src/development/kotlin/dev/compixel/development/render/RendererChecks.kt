package dev.compixel.development.render

import dev.compixel.forge.*
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.render.RenderBackend
import dev.compixel.render.gl.testing.OpenGlRendererProbe
import dev.compixel.testing.render.RendererAcceptance
import net.minecraft.client.Minecraft

/** Only device discovery and host submission are version-specific. */
internal fun verifyRenderer(): String {
    val directory = Minecraft.getInstance().gameDirectory
    return when (configuredRenderBackend()) {
        RenderBackend.OPENGL -> RendererAcceptance.verify(directory, "opengl", OpenGlRendererProbe::verify)
        RenderBackend.VULKAN -> error("Vulkan is not supported by this adapter")
        RenderBackend.CPU_RASTER -> "CPU reference backend"
    }
}
