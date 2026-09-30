package dev.compixel.forge.render

import com.mojang.blaze3d.vertex.PoseStack

/** Draw framebuffer pixel dimensions through Minecraft's exact GUI projection. */
internal inline fun withFramebufferPixels(pose: PoseStack, metrics: ScreenMetrics, draw: () -> Unit) {
    pose.pushPose()
    try {
        val scale = metrics.renderCoordinate(1f)
        pose.scale(scale, scale, 1f)
        draw()
    } finally {
        pose.popPose()
    }
}
