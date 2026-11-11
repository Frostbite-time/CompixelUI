package dev.compixel.forge

import dev.compixel.platform.UiThread
import net.minecraft.client.Minecraft

/**
 * Minecraft's client thread as the UI's thread: Compose composes, lays out and draws where the game ticks and renders,
 * so content and game need no handoff between threads. UI coroutines resume among the client's own tasks.
 */
internal object ClientThread : UiThread {
    override val isCurrent: Boolean
        get() = Minecraft.getInstance().isSameThread

    // Queues also on the client thread, where execute() would run the task inside this call.
    override fun post(task: Runnable) = Minecraft.getInstance().schedule(task)
}
