package dev.compixel.host

import kotlin.test.*
import org.junit.jupiter.api.Test

class ScreenMetricsTest {
    @Test
    fun `GUI input conversion does not use Compose density`() {
        val metrics = ScreenMetrics(1280, 960, 320, 240, 4f, 1f)
        assertEquals(4f, metrics.viewport.density)
        assertEquals(400f, metrics.pixelX(100.0))
        assertEquals(200f, metrics.pixelY(50.0))
    }

    @Test
    fun `rounded GUI dimensions map to framebuffer edges`() {
        val metrics = ScreenMetrics(1001, 721, 334, 241, 3f, 1f)
        assertEquals(1001f, metrics.pixelX(334.0))
        assertEquals(721f, metrics.pixelY(241.0))
        assertEquals(334.0, metrics.inputX(1001f))
        assertEquals(241.0, metrics.inputY(721f))
    }

    @Test
    fun `render coordinates ignore rounded GUI dimensions and Compose density`() {
        val metrics = ScreenMetrics(1927, 1447, 482, 362, 4f, 1f)
        val changed = metrics.copy(guiWidth = 483, guiHeight = 363, guiUnitsPerDp = 0.5f, minimumUiDensity = 3f)
        assertNotEquals(metrics.inputX(1919.5f), changed.inputX(1919.5f))
        assertNotEquals(metrics.inputY(1439.5f), changed.inputY(1439.5f))
        for (current in listOf(metrics, changed)) {
            assertEquals(479.875f, current.renderCoordinate(1919.5f))
            assertEquals(359.875, current.renderCoordinate(1439.5))
        }
    }

    @Test
    fun `changing dp scale only changes layout density`() {
        val a = ScreenMetrics(1280, 960, 320, 240, 4f, 1f)
        val b = a.copy(guiUnitsPerDp = 0.5f)
        assertEquals(a.pixelX(73.5), b.pixelX(73.5))
        assertNotEquals(a.viewport.density, b.viewport.density)
    }

    @Test
    fun `minimum density keeps small-window text readable without changing input coordinates`() {
        val original = ScreenMetrics(480, 720, 480, 720, 1f, 1f)
        val readable = original.copy(minimumUiDensity = 1f)
        assertEquals(1f, original.viewport.density)
        assertEquals(1f, readable.viewport.density)
        assertEquals(original.pixelX(120.0), readable.pixelX(120.0))
        assertEquals(original.pixelY(360.0), readable.pixelY(360.0))
    }

    @Test
    fun `invalid metrics fail immediately`() {
        assertFailsWith<IllegalArgumentException> { ScreenMetrics(100, 100, 0, 100, 1f, 1f) }
        assertFailsWith<IllegalArgumentException> { ScreenMetrics(100, 100, 100, 100, 1f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ScreenMetrics(0, 100, 100, 100, 1f, 1f) }
    }
}
