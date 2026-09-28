package dev.composemc.forge.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.*
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import dev.composemc.render.*
import dev.composemc.render.vulkan.VulkanFrameRenderer
import dev.composemc.render.vulkan.VulkanImageBarriers
import dev.composemc.render.vulkan.VulkanImageTarget
import org.jetbrains.skia.Image
import org.lwjgl.vulkan.VK12

/** Owns Minecraft targets and command-pool scheduling; rendering and barriers are shared. */
internal class VulkanScreenFrameRenderer(override val profiler: UiFrameProfiler?) : ScreenFrameRenderer {
    private data class Target(
        val texture: VulkanGpuTexture,
        val view: GpuTextureView,
        val sampler: GpuSampler,
        val image: VulkanImageTarget,
    )

    private val renderer = VulkanFrameRenderer(minecraftVulkanHandles())
    private var target: Target? = null
    private var allocations = 0L
    private var closed = false
    override val statistics
        get() =
            renderer.statistics.copy(
                surfaceAllocations = allocations,
                liveSurfaces = if (target == null) 0 else 1,
            )

    override val needsFrame
        get() = renderer.needsFrame

    // Minecraft submits its recorded work at the end of the frame, so a texture drawn in one
    // frame is copied in the next: the acquire runs after that host batch, the release before
    // the next one. Both queue orders follow submission order on the shared graphics queue.
    override val nativeSnapshots =
        object : NativeSnapshots {
            override val immediate = false

            override fun snapshot(texture: GpuTexture, width: Int, height: Int): Image {
                RenderSystem.assertOnRenderThread()
                check(!closed)
                val source = texture as VulkanGpuTexture
                val image =
                    VulkanImageTarget(
                        source.vkImage(),
                        source.getWidth(0),
                        source.getHeight(0),
                        VulkanConst.toVk(source.format),
                        VulkanConst.textureUsageToVk(source.usage(), source.format),
                    )
                val device = minecraftVulkanDevice()
                val encoder = device.createCommandEncoder()
                val acquire = encoder.allocateAndBeginTransientCommandBuffer()
                VulkanImageBarriers.acquireForSnapshot(acquire, image.image)
                check(VK12.vkEndCommandBuffer(acquire) == VK12.VK_SUCCESS)
                device.graphicsQueue().beginSubmit().use { it.executeCommands(acquire) }
                // Minecraft renders GUI targets bottom-up on Vulkan as well.
                val copy =
                    renderer.snapshotImage(
                        image,
                        VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        width,
                        height,
                        bottomUp = true,
                    )
                val release = encoder.allocateAndBeginTransientCommandBuffer()
                VulkanImageBarriers.releaseAfterSnapshot(release, image.image)
                check(VK12.vkEndCommandBuffer(release) == VK12.VK_SUCCESS)
                encoder.execute(release)
                return copy
            }

            override fun release(image: Image) = renderer.releaseImage(image)
        }

    private fun target(width: Int, height: Int): Target {
        target
            ?.takeIf { it.image.width == width && it.image.height == height }
            ?.let {
                return it
            }
        releaseTarget()
        val device = RenderSystem.getDevice()
        val texture =
            device.createTexture(
                { "composemc-vulkan-frame" },
                GpuTexture.USAGE_TEXTURE_BINDING or
                    GpuTexture.USAGE_RENDER_ATTACHMENT or
                    GpuTexture.USAGE_COPY_SRC or
                    GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1,
            ) as VulkanGpuTexture
        try {
            val view = device.createTextureView(texture)
            try {
                val sampler =
                    device.createSampler(
                        AddressMode.CLAMP_TO_EDGE,
                        AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR,
                        FilterMode.LINEAR,
                        1,
                        java.util.OptionalDouble.empty(),
                    )
                try {
                    // Submit Minecraft's initial UNDEFINED -> GENERAL transition before borrowing the image.
                    minecraftVulkanDevice().createCommandEncoder().submit()
                    val image =
                        VulkanImageTarget(
                            texture.vkImage(),
                            width,
                            height,
                            VulkanConst.toVk(texture.format),
                            VulkanConst.textureUsageToVk(texture.usage(), texture.format),
                        )
                    return Target(texture, view, sampler, image).also {
                        target = it
                        allocations++
                    }
                } catch (error: Throwable) {
                    sampler.close()
                    throw error
                }
            } catch (error: Throwable) {
                view.close()
                throw error
            }
        } catch (error: Throwable) {
            texture.close()
            throw error
        }
    }

    override fun render(frame: RecordedFrame) {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        val output = target(frame.viewport.width, frame.viewport.height)
        val device = minecraftVulkanDevice()
        val encoder = device.createCommandEncoder()
        val acquire = encoder.allocateAndBeginTransientCommandBuffer()
        VulkanImageBarriers.acquireForRendering(acquire, output.image.image)
        check(VK12.vkEndCommandBuffer(acquire) == VK12.VK_SUCCESS)
        // The host's current pool owns this buffer. Its end-of-frame fence covers
        // this earlier submission to the same queue; no pool rotation or CPU wait.
        device.graphicsQueue().beginSubmit().use { it.executeCommands(acquire) }
        renderer.render(frame, output.image)
        val release = encoder.allocateAndBeginTransientCommandBuffer()
        VulkanImageBarriers.releaseForSampling(release, output.image.image)
        check(VK12.vkEndCommandBuffer(release) == VK12.VK_SUCCESS)
        encoder.execute(release)
    }

    override fun present(destination: ScreenRenderDestination) {
        target?.let { presentPremultiplied(destination, it.view, it.sampler, flipY = false) }
    }

    override fun reset() {
        releaseTarget()
        renderer.reset()
    }

    private fun releaseTarget() {
        target?.let { old ->
            FrameRetirement.afterFrame {
                old.view.close()
                old.sampler.close()
                old.texture.close()
            }
        }
        target = null
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        releaseTarget()
        FrameRetirement.afterFrame { renderer.close() }
        closed = true
    }
}
