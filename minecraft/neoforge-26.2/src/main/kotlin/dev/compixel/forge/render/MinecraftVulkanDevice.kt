package dev.compixel.forge.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vulkan.VulkanDevice
import dev.compixel.render.vulkan.VulkanDeviceHandles
import org.lwjgl.vulkan.VK

/** Version-specific discovery only; Skia context ownership lives in render-vulkan. */
internal fun minecraftVulkanDevice(): VulkanDevice {
    RenderSystem.assertOnRenderThread()
    return RenderSystem.getDevice().backend as? VulkanDevice
        ?: error("The Vulkan UI backend requires a Vulkan Minecraft device")
}

internal fun minecraftVulkanHandles(): VulkanDeviceHandles {
    val backend = minecraftVulkanDevice()
    val queue = backend.graphicsQueue()
    val device = backend.vkDevice()
    val functions = VK.getFunctionProvider()
    return VulkanDeviceHandles(
        backend.instance().vkInstance().address(),
        device.physicalDevice.address(),
        device.address(),
        queue.vkQueue().address(),
        queue.queueFamilyIndex(),
        functions.getFunctionAddress("vkGetInstanceProcAddr"),
        functions.getFunctionAddress("vkGetDeviceProcAddr"),
    )
}

internal fun minecraftUsesVulkan(): Boolean = RenderSystem.getDevice().backend is VulkanDevice
