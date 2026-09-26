package dev.composemc.testing.suite

import kotlin.math.abs

/** A captured framebuffer as top-left-origin ARGB, independent of each version's NativeImage byte order. */
class ScreenPixels(val width: Int, val height: Int, private val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) {
            "Pixel buffer does not match ${width}x$height"
        }
    }

    fun argb(x: Int, y: Int): Int = argb[y * width + x]

    fun rgb(x: Int, y: Int): Int = argb(x, y) and 0xFFFFFF

    fun distinctColors(step: Int = 1, left: Int = 0, top: Int = 0, right: Int = width, bottom: Int = height): Int {
        val colors = HashSet<Int>()
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height) step step) for (x in
            left.coerceAtLeast(0) until right.coerceAtMost(width) step step) colors += rgb(x, y)
        return colors.size
    }

    fun region(left: Int, top: Int, width: Int, height: Int): IntArray {
        require(
            left >= 0 &&
                top >= 0 &&
                width > 0 &&
                height > 0 &&
                left + width <= this.width &&
                top + height <= this.height
        ) {
            "Region $left,$top ${width}x$height is outside the ${this.width}x${this.height} capture"
        }
        return IntArray(width * height) { argb(left + it % width, top + it / width) }
    }
}

/** A laid-out rectangle in framebuffer pixels: [left, right) by [top, bottom), top-left origin. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left < right && top < bottom) { "Empty pixel rectangle $left,$top..$right,$bottom" }
    }
}

/** Pixel expectations shared by every adapter's acceptance captures. */
object SuitePixels {
    /** Drawn by Minecraft's own GUI after Compose, in GUI units, to catch projection and viewport pollution. */
    const val MARKER_ARGB: Int = 0xFF22CC66.toInt()
    const val MARKER_GUI_SIZE = 4

    /** The development HUD panel's opaque color, which nothing in the test world draws. */
    const val HUD_ARGB: Int = 0xFFB02A8C.toInt()

    /**
     * The HUD panel keeps its exact color just inside its corners, where neither its item nor its text reaches, so it
     * sits where it was laid out. Its native item covers at least a fifth of the item's own bounds.
     */
    fun requireHud(pixels: ScreenPixels, name: String, panel: PixelRect, item: PixelRect) {
        val corners =
            listOf(
                panel.left + 2 to panel.top + 2,
                panel.right - 3 to panel.top + 2,
                panel.left + 2 to panel.bottom - 3,
                panel.right - 3 to panel.bottom - 3,
            )
        for ((x, y) in corners) {
            check(near(pixels.argb(x, y), HUD_ARGB, 4)) {
                "The HUD panel is missing or misplaced in $name at ($x,$y): ${pixels.rgb(x, y).toString(16)}"
            }
        }
        val region = pixels.region(item.left, item.top, item.right - item.left, item.bottom - item.top)
        val drawn = region.count { !near(it, HUD_ARGB, 48) }
        check(drawn >= region.size / 5) { "The HUD item is missing in $name: $drawn of ${region.size} pixels" }
    }

    /** Where the HUD panel was laid out, the hidden HUD left none of its color. */
    fun requireHudHidden(pixels: ScreenPixels, name: String, panel: PixelRect) {
        val region = pixels.region(panel.left, panel.top, panel.right - panel.left, panel.bottom - panel.top)
        val shown = region.count { near(it, HUD_ARGB, 4) }
        check(shown <= region.size / 100) { "The hidden HUD still drew $shown of ${region.size} pixels in $name" }
    }

    private fun near(argb: Int, expected: Int, tolerance: Int) =
        (0..2).all { channel ->
            abs((argb ushr (channel * 8) and 255) - (expected ushr (channel * 8) and 255)) <= tolerance
        }

    fun requireContent(pixels: ScreenPixels, name: String, minimumColors: Int = 16) {
        val colors = pixels.distinctColors(step = 8)
        check(colors > minimumColors) { "Blank framebuffer in $name: $colors colors" }
    }

    fun requireMarker(pixels: ScreenPixels, name: String, guiWidth: Int) {
        val edge = MARKER_GUI_SIZE * pixels.width / guiWidth
        check(pixels.rgb(2, 2) == MARKER_ARGB and 0xFFFFFF) {
            "Minecraft drawing after Compose is missing in $name: ${pixels.rgb(2, 2).toString(16)}"
        }
        check(pixels.rgb(edge + 2, 2) != MARKER_ARGB and 0xFFFFFF) {
            "Minecraft projection/viewport was not restored in $name"
        }
    }

    fun requireTooltipImage(pixels: ScreenPixels, name: String, left: Int, top: Int, right: Int, bottom: Int) {
        val colors = pixels.distinctColors(step = 1, left = left, top = top, right = right, bottom = bottom)
        check(colors > 8) { "Native tooltip image is blank in $name: $colors colors" }
    }

    /** The rotated, clipped, translucent chest preview must stay visibly warm (red above green). */
    fun previewRegion(pixels: ScreenPixels, name: String, left: Int, top: Int, width: Int, height: Int): IntArray {
        val region = pixels.region(left, top, width, height)
        val warm = region.count {
            val red = it ushr 16 and 255
            red > (it ushr 8 and 255) && red > 40
        }
        check(warm > 50) { "Rotated/clipped item preview is missing or faded in $name: $warm warm pixels" }
        return region
    }

    /** Repaints, scrolling and reloads may not change more than 1% of the preview by more than 2 levels. */
    fun requireSameRegion(reference: IntArray, current: IntArray, name: String) {
        check(reference.size == current.size) { "Preview size changed in $name" }
        val changed =
            current.indices.count { index ->
                (0..2).any { channel ->
                    abs((current[index] ushr (channel * 8) and 255) - (reference[index] ushr (channel * 8) and 255)) > 2
                }
            }
        check(changed <= current.size / 100) {
            "Item alpha or clipping changed in $name: $changed of ${current.size} pixels"
        }
    }
}
