package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import dev.composemc.render.vulkan.VulkanDeviceHandles
import org.lwjgl.vulkan.VK

/** Version-specific discovery only; Skia context ownership lives in render-vulkan. */
internal fun minecraftVulkanDevice(): VulkanDevice {
    RenderSystem.assertOnRenderThread()
    return (RenderSystem.getDevice() as com.mojang.renderpearl.frontend.FrontendGpuDevice).backend as? VulkanDevice
        ?: error("The Vulkan UI backend requires a Vulkan Minecraft device")
}

internal fun minecraftVulkanHandles(): VulkanDeviceHandles {
    val backend = minecraftVulkanDevice()
    val queue = backend.graphicsQueue()
    val device = backend.vkDevice()
    val functions = VK.getFunctionProvider()
    return VulkanDeviceHandles(backend.instance().vkInstance().address(), device.physicalDevice.address(),
        device.address(), queue.vkQueue().address(), queue.queueFamilyIndex(),
        functions.getFunctionAddress("vkGetInstanceProcAddr"), functions.getFunctionAddress("vkGetDeviceProcAddr"))
}

internal fun minecraftUsesVulkan(): Boolean = (RenderSystem.getDevice() as com.mojang.renderpearl.frontend.FrontendGpuDevice).backend is VulkanDevice
