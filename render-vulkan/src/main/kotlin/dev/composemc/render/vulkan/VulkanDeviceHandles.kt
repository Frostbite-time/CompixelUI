package dev.composemc.render.vulkan

import org.lwjgl.vulkan.VK12

/** Borrowed handles. The host owns their lifetime, queue submission and thread synchronization. */
data class VulkanDeviceHandles(
    val instance: Long,
    val physicalDevice: Long,
    val device: Long,
    val graphicsQueue: Long,
    val graphicsQueueFamily: Int,
    val getInstanceProcAddress: Long,
    val getDeviceProcAddress: Long,
    val apiVersion: Int = VK12.VK_API_VERSION_1_2,
) {
    init {
        require(instance != 0L && physicalDevice != 0L && device != 0L && graphicsQueue != 0L)
        require(graphicsQueueFamily >= 0)
        require(getInstanceProcAddress != 0L && getDeviceProcAddress != 0L)
    }
}

/** Host-owned, single-sample RGBA8 image; COLOR_ATTACHMENT_OPTIMAL on entry and return. */
data class VulkanImageTarget(val image: Long, val width: Int, val height: Int, val format: Int, val usage: Int) {
    init {
        require(image != 0L && width > 0 && height > 0)
        require(format == VK12.VK_FORMAT_R8G8B8A8_UNORM) { "The UI renderer requires an RGBA8 UNORM target" }
        val required = VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
            VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT
        require(usage and required == required) { "Skia targets require attachment and transfer usages" }
    }
}
