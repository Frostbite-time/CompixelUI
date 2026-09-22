package dev.composemc.forge

import dev.composemc.platform.Viewport

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

/** GLFW characters arrive as UTF-16 through Screen in 1.20.1. */
internal class CommittedCharacters {
    private var pendingHigh: Char? = null
    fun accept(character: Char): String? {
        val high = pendingHigh
        pendingHigh = null
        return when {
            character.isHighSurrogate() -> { pendingHigh = character; null }
            character.isLowSurrogate() -> high?.let { "$it$character" }
            character.isISOControl() -> null
            else -> character.toString()
        }
    }
    fun reset() { pendingHigh = null }
}
