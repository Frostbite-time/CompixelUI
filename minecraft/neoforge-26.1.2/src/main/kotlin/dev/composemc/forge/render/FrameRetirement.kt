package dev.composemc.forge.render

import com.mojang.blaze3d.systems.RenderSystem
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent

/** Extracted GUI commands borrow views until Minecraft has rendered this frame. */
internal object FrameRetirement {
    private val pending = ArrayDeque<() -> Unit>()
    private var stopping = false
    fun afterFrame(release: () -> Unit) {
        RenderSystem.assertOnRenderThread()
        if (stopping) { release(); return }
        pending.addLast(release)
    }
    fun finish(event: RenderFrameEvent.Post) {
        RenderSystem.assertOnRenderThread()
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        RenderSystem.queueFencedTask(Runnable { batch.forEach { it() } })
    }

    fun shutdown(event: ClientStoppingEvent) {
        RenderSystem.assertOnRenderThread()
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.createFence().use { fence ->
            org.lwjgl.opengl.GL11.glFlush()
            check(fence.awaitCompletion(5_000_000_000L)) { "GPU did not finish before UI shutdown" }
        }
        RenderSystem.executePendingTasks()
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        stopping = true
    }
}
