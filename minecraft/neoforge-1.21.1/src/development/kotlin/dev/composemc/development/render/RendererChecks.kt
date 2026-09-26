package dev.composemc.development.render

import dev.composemc.forge.*
import dev.composemc.render.RenderBackend
import dev.composemc.render.gl.testing.OpenGlRendererProbe
import dev.composemc.testing.render.RendererAcceptance
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
