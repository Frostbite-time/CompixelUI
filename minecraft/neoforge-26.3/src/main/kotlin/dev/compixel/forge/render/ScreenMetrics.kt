package dev.compixel.forge.render

import dev.compixel.platform.Viewport

/** GUI coordinates, framebuffer pixels and Compose dp are deliberately separate units. */
internal data class ScreenMetrics(
    val framebufferWidth: Int,
    val framebufferHeight: Int,
    val guiWidth: Int,
    val guiHeight: Int,
    val guiScale: Float,
    val guiUnitsPerDp: Float,
    val minimumUiDensity: Float = 0f,
) {
    init {
        require(guiWidth > 0 && guiHeight > 0)
        require(guiUnitsPerDp.isFinite() && guiUnitsPerDp > 0f)
        require(guiScale.isFinite() && guiScale > 0f)
        require(minimumUiDensity.isFinite() && minimumUiDensity >= 0f)
    }

    val viewport = Viewport(framebufferWidth, framebufferHeight, maxOf(guiScale * guiUnitsPerDp, minimumUiDensity))

    fun pixelX(guiX: Double): Float = (guiX * framebufferWidth / guiWidth).toFloat()

    fun pixelY(guiY: Double): Float = (guiY * framebufferHeight / guiHeight).toFloat()
}
