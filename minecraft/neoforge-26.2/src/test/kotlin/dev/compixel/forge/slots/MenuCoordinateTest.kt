package dev.compixel.forge.slots

import androidx.compose.ui.geometry.Rect
import dev.compixel.forge.render.ScreenMetrics
import kotlin.math.roundToInt
import kotlin.test.*
import org.junit.jupiter.api.Test

class MenuCoordinateTest {
    @Test
    fun `native slot geometry and input bounds meet at the same physical pixels`() {
        for ((width, height) in listOf(1927 to 1447, 2560 to 1440, 1366 to 768, 1920 to 1080)) {
            for (scale in 1..9) {
                val metrics =
                    ScreenMetrics(
                        width,
                        height,
                        (width + scale - 1) / scale,
                        (height + scale - 1) / scale,
                        scale.toFloat(),
                        1f,
                    )
                val pixels = Rect(width - 96f, height - 96f, width - 32f, height - 32f)
                val input = menuInputBounds(pixels, metrics)
                val rendered = menuRenderBounds(pixels, metrics)
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
    fun `a slot in the right quarter keeps its native GUI unit on odd windows`() {
        // 1366 px at GUI scale 3 rounds up to 456 units. A dp-aligned slot at 1200 px is exactly GUI 400, while
        // the rounded mouse mapping gives 400.6, which would move its native slot one unit (3 px) to the right.
        val pixels = Rect(1176f, 300f, 1224f, 348f)
        val metrics = ScreenMetrics(1366, 768, 456, 256, 3f, 1f)
        val rendered = menuRenderBounds(pixels, metrics)
        val input = menuInputBounds(pixels, metrics)
        assertEquals(MenuSlotBounds(392.0, 100.0, 408.0, 116.0), rendered)
        assertEquals(400, ((rendered.left + rendered.right) / 2).roundToInt())
        assertEquals(401, ((input.left + input.right) / 2).roundToInt())
    }

    @Test
    fun `odd-window render bounds do not reuse rounded mouse coordinates`() {
        val pixels = Rect(1800f, 1320f, 1864f, 1384f)
        val metrics = ScreenMetrics(1927, 1447, 482, 362, 4f, 1f)
        val rendered = menuRenderBounds(pixels, metrics)
        val input = menuInputBounds(pixels, metrics)
        assertEquals(MenuSlotBounds(450.0, 330.0, 466.0, 346.0), rendered)
        assertNotEquals(rendered, input)
    }
}
