package dev.compixel.forge.render

import com.mojang.blaze3d.vertex.PoseStack

/** Draw framebuffer pixel dimensions through Minecraft's exact GUI projection. */
internal inline fun withFramebufferPixels(pose: PoseStack, metrics: ScreenMetrics, draw: () -> Unit) {
    pose.pushPose()
    try {
        val scale = 1f / metrics.guiScale
        pose.scale(scale, scale, 1f)
        draw()
    } finally {
        pose.popPose()
    }
}
