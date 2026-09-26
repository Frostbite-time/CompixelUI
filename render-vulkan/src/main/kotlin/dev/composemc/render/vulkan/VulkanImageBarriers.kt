package dev.composemc.render.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkImageMemoryBarrier

/** Records barriers only. Command-pool lifetime, command-buffer end and submission belong to the host. */
object VulkanImageBarriers {
    @JvmStatic
    fun acquireForRendering(commandBuffer: VkCommandBuffer, image: Long) =
        transition(
            commandBuffer,
            image,
            VK12.VK_IMAGE_LAYOUT_GENERAL,
            VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK12.VK_ACCESS_MEMORY_READ_BIT or VK12.VK_ACCESS_MEMORY_WRITE_BIT,
            VK12.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT or VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        )

    @JvmStatic
    fun releaseForSampling(commandBuffer: VkCommandBuffer, image: Long) =
        transition(
            commandBuffer,
            image,
            VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK12.VK_IMAGE_LAYOUT_GENERAL,
            VK12.VK_ACCESS_MEMORY_WRITE_BIT,
            VK12.VK_ACCESS_SHADER_READ_BIT,
            VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        )

    /** Makes host writes visible to Skia's transfer copy submitted afterwards. */
    @JvmStatic
    fun acquireForSnapshot(commandBuffer: VkCommandBuffer, image: Long) =
        transition(
            commandBuffer,
            image,
            VK12.VK_IMAGE_LAYOUT_GENERAL,
            VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK12.VK_ACCESS_MEMORY_WRITE_BIT,
            VK12.VK_ACCESS_TRANSFER_READ_BIT,
            VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
        )

    /**
     * Returns a snapshotted image to GENERAL for host writes. Skia may leave it in a copy layout, so its contents are
     * discarded: the host must redraw it completely before the next snapshot.
     */
    @JvmStatic
    fun releaseAfterSnapshot(commandBuffer: VkCommandBuffer, image: Long) =
        transition(
            commandBuffer,
            image,
            VK12.VK_IMAGE_LAYOUT_UNDEFINED,
            VK12.VK_IMAGE_LAYOUT_GENERAL,
            0,
            VK12.VK_ACCESS_MEMORY_READ_BIT or VK12.VK_ACCESS_MEMORY_WRITE_BIT,
            VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        )

    private fun transition(
        commandBuffer: VkCommandBuffer,
        image: Long,
        from: Int,
        to: Int,
        sourceAccess: Int,
        destinationAccess: Int,
        destinationStage: Int,
    ) {
        require(image != 0L)
        MemoryStack.stackPush().use { stack ->
            val barrier =
                VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(from)
                    .newLayout(to)
                    .srcAccessMask(sourceAccess)
                    .dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
            barrier
                .subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
            VK12.vkCmdPipelineBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                destinationStage,
                0,
                null,
                null,
                barrier,
            )
        }
    }
}
