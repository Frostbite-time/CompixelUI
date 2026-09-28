package dev.composemc.render.vulkan.testing

import dev.composemc.render.RecordedFrame
import dev.composemc.render.vulkan.VulkanDeviceHandles
import dev.composemc.render.vulkan.VulkanFrameRenderer
import dev.composemc.render.vulkan.VulkanImageBarriers
import dev.composemc.render.vulkan.VulkanImageTarget
import dev.composemc.testing.render.RendererPixels
import dev.composemc.testing.render.RendererProbeResult
import kotlin.concurrent.thread
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK12.*

/** Runs on the host render thread after its pending commands have been submitted. */
object VulkanRendererProbe {
    fun verify(
        device: VkDevice,
        handles: VulkanDeviceHandles,
        frame: RecordedFrame,
        expectedRgba: ByteArray,
    ): RendererProbeResult {
        require(device.address() == handles.device)
        val queue = VkQueue(handles.graphicsQueue, device)
        success(vkQueueWaitIdle(queue), "wait for host graphics queue")
        val cycles = 12
        var pixels = ByteArray(0)
        var worstPixels = 0
        var worstMean = 0.0
        ProbeTarget(device, queue, handles.graphicsQueueFamily, frame.viewport.width, frame.viewport.height).use {
            target ->
            repeat(cycles) { cycle ->
                val renderer = VulkanFrameRenderer(handles)
                try {
                    repeat(3) { iteration ->
                        target.submit { VulkanImageBarriers.acquireForRendering(it, target.image.image) }
                        renderer.render(frame, target.image)
                        pixels = target.readRgba()
                        val difference =
                            RendererPixels.verify(expectedRgba, pixels, "CPU/Vulkan cycle $cycle, iteration $iteration")
                        worstPixels = maxOf(worstPixels, difference.differentPixels)
                        worstMean = maxOf(worstMean, difference.meanChannelError)
                        check(!renderer.needsFrame && renderer.statistics.lastFrameGeneration == frame.generation)
                        check(renderer.statistics.fullFrameUploads == 0L)
                        if (iteration == 1) {
                            renderer.reset()
                            check(renderer.needsFrame)
                        }
                    }
                    check(renderer.statistics.renderedFrames == 3L)
                } finally {
                    // Skia objects may be released only after the last submission has completed.
                    success(vkQueueWaitIdle(queue), "retire probe renderer")
                    renderer.close()
                }
                // The same borrowed image survives context destruction and is used by the next cycle.
            }
            verifyRetirement(queue, handles, target, frame)
        }
        return RendererProbeResult(pixels, cycles, worstPixels, worstMean)
    }

    /**
     * Compose records snapshots on its own thread and may drop those pictures after the renderer has released the
     * image. The final release must still happen on this thread, which owns the Skia context.
     */
    private fun verifyRetirement(
        queue: VkQueue,
        handles: VulkanDeviceHandles,
        target: ProbeTarget,
        frame: RecordedFrame,
    ) {
        val renderer = VulkanFrameRenderer(handles)
        try {
            repeat(3) {
                target.submit { VulkanImageBarriers.acquireForSnapshot(it, target.image.image) }
                val image =
                    renderer.snapshotImage(
                        target.image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        target.image.width,
                        target.image.height,
                        bottomUp = false,
                    )
                target.submit { VulkanImageBarriers.releaseAfterSnapshot(it, target.image.image) }
                val picture =
                    PictureRecorder().use { recorder ->
                        recorder
                            .beginRecording(Rect.makeWH(image.width.toFloat(), image.height.toFloat()))
                            .drawImage(image, 0f, 0f)
                        recorder.finishRecordingAsPicture()
                    }
                renderer.releaseImage(image)
                check(!image.isClosed && renderer.statistics.retiredNativeImages == 1) {
                    "A snapshot was closed while a picture still referred to it"
                }
                thread { picture.close() }.join()
                target.submit { VulkanImageBarriers.acquireForRendering(it, target.image.image) }
                renderer.render(frame, target.image)
                target.submit { VulkanImageBarriers.releaseForSampling(it, target.image.image) }
                check(image.isClosed && renderer.statistics.liveNativeImages == 0) {
                    "An unreferenced retired snapshot stayed open: ${renderer.statistics}"
                }
            }
        } finally {
            success(vkQueueWaitIdle(queue), "retire probe renderer")
            renderer.close()
        }
        check(renderer.statistics.let { it.liveNativeImages == 0 && it.strandedNativeImages == 0 }) {
            "The renderer kept snapshots after closing: ${renderer.statistics}"
        }
    }
}

/** Owns only probe resources. The Minecraft device, queue and command pools remain borrowed. */
private class ProbeTarget(
    private val device: VkDevice,
    private val queue: VkQueue,
    family: Int,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private var imageHandle = VK_NULL_HANDLE
    private var imageMemory = VK_NULL_HANDLE
    private var buffer = VK_NULL_HANDLE
    private var bufferMemory = VK_NULL_HANDLE
    private var pool = VK_NULL_HANDLE
    private lateinit var command: VkCommandBuffer
    private val byteSize = width.toLong() * height * 4
    private val usage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
            VK_IMAGE_USAGE_TRANSFER_DST_BIT or
            VK_IMAGE_USAGE_SAMPLED_BIT
    val image
        get() = VulkanImageTarget(imageHandle, width, height, VK_FORMAT_R8G8B8A8_UNORM, usage)

    init {
        try {
            MemoryStack.stackPush().use { stack ->
                val output = stack.mallocLong(1)
                val imageInfo =
                    VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_R8G8B8A8_UNORM)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(usage)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                imageInfo.extent().set(width, height, 1)
                success(vkCreateImage(device, imageInfo, null, output), "create probe image")
                imageHandle = output[0]
                val requirements = VkMemoryRequirements.malloc(stack)
                vkGetImageMemoryRequirements(device, imageHandle, requirements)
                imageMemory = allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                success(vkBindImageMemory(device, imageHandle, imageMemory, 0), "bind probe image")

                val bufferInfo =
                    VkBufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(byteSize)
                        .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                success(vkCreateBuffer(device, bufferInfo, null, output), "create readback buffer")
                buffer = output[0]
                vkGetBufferMemoryRequirements(device, buffer, requirements)
                bufferMemory =
                    allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                success(vkBindBufferMemory(device, buffer, bufferMemory, 0), "bind readback buffer")

                val poolInfo =
                    VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .queueFamilyIndex(family)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                success(vkCreateCommandPool(device, poolInfo, null, output), "create probe command pool")
                pool = output[0]
                val allocation =
                    VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(pool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1)
                val commands = stack.mallocPointer(1)
                success(vkAllocateCommandBuffers(device, allocation, commands), "allocate probe commands")
                command = VkCommandBuffer(commands[0], device)
            }
            submit {
                transition(
                    it,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL,
                    0,
                    VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                )
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun allocate(requirements: VkMemoryRequirements, flags: Int): Long =
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceMemoryProperties.malloc(stack)
            vkGetPhysicalDeviceMemoryProperties(device.physicalDevice, properties)
            val type =
                (0 until properties.memoryTypeCount()).firstOrNull {
                    requirements.memoryTypeBits() and (1 shl it) != 0 &&
                        properties.memoryTypes(it).propertyFlags() and flags == flags
                } ?: error("No Vulkan memory type for probe flags $flags")
            val allocation =
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(type)
            val output = stack.mallocLong(1)
            success(vkAllocateMemory(device, allocation, null, output), "allocate probe memory")
            output[0]
        }

    fun submit(record: (VkCommandBuffer) -> Unit) {
        MemoryStack.stackPush().use { stack ->
            success(vkResetCommandBuffer(command, 0), "reset probe commands")
            val begin =
                VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            success(vkBeginCommandBuffer(command, begin), "begin probe commands")
            record(command)
            success(vkEndCommandBuffer(command), "end probe commands")
            val submit =
                VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(command.address()))
            success(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "submit probe commands")
            // Deliberate synchronization for test readback; production rendering remains asynchronous.
            success(vkQueueWaitIdle(queue), "complete probe commands")
        }
    }

    fun readRgba(): ByteArray {
        submit { commands ->
            VulkanImageBarriers.releaseForSampling(commands, imageHandle)
            transition(
                commands,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
            )
            MemoryStack.stackPush().use { stack ->
                val region = VkBufferImageCopy.calloc(1, stack)
                region
                    .imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
                region.imageExtent().set(width, height, 1)
                vkCmdCopyImageToBuffer(commands, imageHandle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region)
                val barrier =
                    VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(buffer)
                        .offset(0)
                        .size(byteSize)
                vkCmdPipelineBarrier(
                    commands,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    null,
                    barrier,
                    null,
                )
            }
            transition(
                commands,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            )
        }
        return MemoryStack.stackPush().use { stack ->
            val address = stack.mallocPointer(1)
            success(vkMapMemory(device, bufferMemory, 0, byteSize, 0, address), "map probe readback")
            try {
                ByteArray(byteSize.toInt()).also { MemoryUtil.memByteBuffer(address[0], it.size).get(it) }
            } finally {
                vkUnmapMemory(device, bufferMemory)
            }
        }
    }

    private fun transition(
        commands: VkCommandBuffer,
        from: Int,
        to: Int,
        sourceAccess: Int,
        destinationAccess: Int,
        sourceStage: Int,
        destinationStage: Int,
    ) {
        MemoryStack.stackPush().use { stack ->
            val barrier =
                VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(from)
                    .newLayout(to)
                    .srcAccessMask(sourceAccess)
                    .dstAccessMask(destinationAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(imageHandle)
            barrier
                .subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
            vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, null, null, barrier)
        }
    }

    override fun close() {
        vkQueueWaitIdle(queue)
        if (pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, pool, null)
        if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer, null)
        if (bufferMemory != VK_NULL_HANDLE) vkFreeMemory(device, bufferMemory, null)
        if (imageHandle != VK_NULL_HANDLE) vkDestroyImage(device, imageHandle, null)
        if (imageMemory != VK_NULL_HANDLE) vkFreeMemory(device, imageMemory, null)
    }
}

private fun success(result: Int, operation: String) =
    check(result == VK_SUCCESS) { "$operation failed: VkResult=$result" }
