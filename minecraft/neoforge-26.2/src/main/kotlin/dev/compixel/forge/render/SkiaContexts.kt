package dev.compixel.forge.render

import dev.compixel.render.gl.OpenGlSkia
import dev.compixel.render.vulkan.VulkanSkia

/** Skia's context on the game's graphics device, OpenGL or Vulkan, which every screen and HUD layer shares. */
internal object SkiaContexts {
    /** Skia contexts that exist now; one once any layer has drawn. */
    val live
        get() = OpenGlSkia.liveContexts + VulkanSkia.liveContexts

    /** Releases the images closed layers left behind, once nothing refers to them. Called every client tick. */
    fun collect() {
        if (minecraftUsesVulkan()) VulkanSkia.sharedContexts().forEach { it.collect() }
        else OpenGlSkia.sharedOrNull()?.collect()
    }

    /**
     * Closes the shared context while the game stops, once its last layer has closed and the device has finished their
     * work: Vulkan requires it gone before the device.
     */
    fun closeUnused() {
        if (minecraftUsesVulkan()) VulkanSkia.sharedContexts().filter { it.renderers == 0 }.forEach { it.shutDown() }
        else OpenGlSkia.sharedOrNull()?.takeIf { it.renderers == 0 }?.shutDown()
    }
}
