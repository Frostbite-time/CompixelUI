package dev.compixel.host

import dev.compixel.platform.Viewport

/**
 * Where a host shows Compose: the window's framebuffer pixels, the game's GUI coordinates and Compose dp, which are
 * deliberately separate units. One GUI unit spans [guiScale] framebuffer pixels and one dp spans [guiUnitsPerDp] GUI
 * units; [minimumUiDensity] keeps small windows legible. Game input arrives in GUI coordinates, rounded to whole GUI
 * dimensions, while drawing uses the exact scale.
 */
data class ScreenMetrics(
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

    /** Mouse/input GUI coordinates use the rounded GUI dimensions. */
    fun pixelX(guiX: Double): Float = (guiX * framebufferWidth / guiWidth).toFloat()

    fun pixelY(guiY: Double): Float = (guiY * framebufferHeight / guiHeight).toFloat()

    /** Framebuffer pixels to mouse/input GUI coordinates, inverse to [pixelX] and [pixelY]. */
    fun inputX(pixelX: Float): Double = pixelX.toDouble() * guiWidth / framebufferWidth

    fun inputY(pixelY: Float): Double = pixelY.toDouble() * guiHeight / framebufferHeight

    /** Framebuffer pixels to render GUI coordinates, using the exact projection scale on both axes. */
    fun renderCoordinate(pixels: Double): Double = pixels / guiScale

    fun renderCoordinate(pixels: Float): Float = renderCoordinate(pixels.toDouble()).toFloat()
}
