package dev.compixel.development.render

import dev.compixel.forge.*
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.forge.render.minecraftVulkanDevice
import dev.compixel.forge.render.minecraftVulkanHandles
import dev.compixel.render.RenderBackend
import dev.compixel.render.gl.testing.OpenGlRendererProbe
import dev.compixel.render.vulkan.testing.VulkanRendererProbe
import dev.compixel.testing.render.RendererAcceptance
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
