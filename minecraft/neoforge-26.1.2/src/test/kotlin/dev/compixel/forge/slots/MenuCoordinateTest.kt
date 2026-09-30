package dev.compixel.forge.slots

import androidx.compose.ui.geometry.Rect
import dev.compixel.forge.render.ScreenMetrics
import kotlin.test.*
import org.junit.jupiter.api.Test

class MenuCoordinateTest {
    @Test
    fun `native slot geometry and input bounds meet at the same physical pixels`() {
        for ((width, height) in listOf(1927 to 1447, 2560 to 1440, 1920 to 1080)) {
            for (scale in 1..9) {
                val metrics = ScreenMetrics(
                    width, height, (width + scale - 1) / scale, (height + scale - 1) / scale,
                    scale.toFloat(), 1f,
                )
                val pixels = Rect(width - 96f, height - 96f, width - 32f, height - 32f)
                val input = menuInputBounds(pixels, metrics.guiWidth, metrics.guiHeight, width, height)
                val rendered = menuRenderBounds(pixels, metrics.guiScale)
                assertEquals(pixels.left, metrics.pixelX(input.left), 0.001f)
                assertEquals(pixels.top, metrics.pixelY(input.top), 0.001f)
                assertEquals(pixels.right, metrics.pixelX(input.right), 0.001f)
                assertEquals(pixels.bottom, metrics.pixelY(input.bottom), 0.001f)
                assertEquals(pixels.left.toDouble(), rendered.left * scale, 0.000001)
                assertEquals(pixels.top.toDouble(), rendered.top * scale, 0.000001)
                assertEquals(pixels.right.toDouble(), rendered.right * scale, 0.000001)
                assertEquals(pixels.bottom.toDouble(), rendered.bottom * scale, 0.000001)
            }
        }
    }

    @Test
    fun `odd-window render bounds do not reuse rounded mouse coordinates`() {
        val pixels = Rect(1800f, 1320f, 1864f, 1384f)
        val rendered = menuRenderBounds(pixels, 4f)
        val input = menuInputBounds(pixels, 482, 362, 1927, 1447)
        assertEquals(MenuSlotBounds(450.0, 330.0, 466.0, 346.0), rendered)
        assertNotEquals(rendered, input)
    }
}
