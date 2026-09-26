package dev.composemc.development.render

import dev.composemc.forge.*
import dev.composemc.forge.render.configuredRenderBackend
import dev.composemc.forge.render.minecraftVulkanDevice
import dev.composemc.forge.render.minecraftVulkanHandles
import dev.composemc.render.RenderBackend
import dev.composemc.render.gl.testing.OpenGlRendererProbe
import dev.composemc.render.vulkan.testing.VulkanRendererProbe
import dev.composemc.testing.render.RendererAcceptance
import net.minecraft.client.Minecraft

/** Only device discovery and host submission are version-specific. */
internal fun verifyRenderer(): String {
    val directory = Minecraft.getInstance().gameDirectory
    return when (configuredRenderBackend()) {
        RenderBackend.OPENGL -> RendererAcceptance.verify(directory, "opengl", OpenGlRendererProbe::verify)
        RenderBackend.VULKAN -> {
            val device = minecraftVulkanDevice()
            device.createCommandEncoder().submit()
            val handles = minecraftVulkanHandles()
            RendererAcceptance.verify(directory, "vulkan") { frame, expected ->
                VulkanRendererProbe.verify(device.vkDevice(), handles, frame, expected)
            }
        }
        RenderBackend.CPU_RASTER -> "CPU reference backend"
    }
}
