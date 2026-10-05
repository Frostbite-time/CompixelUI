package dev.compixel.forge.render

import com.mojang.blaze3d.vertex.PoseStack
import dev.compixel.host.ScreenMetrics
import kotlin.test.*
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Test

class FramePresentationTest {
    @Test
    fun `CPU pixel presentation keeps texel centers and edges aligned on odd windows`() {
        val sizes = listOf(1927 to 1447, 2560 to 1440, 3840 to 2160, 1366 to 768, 1920 to 1080)
        for (scale in 1..9) {
            val windows = sizes + (0 until scale).map { 960 + it to 720 + (scale - 1 - it) }
            for ((width, height) in windows) {
                val metrics =
                    ScreenMetrics(
                        width,
                        height,
                        (width + scale - 1) / scale,
                        (height + scale - 1) / scale,
                        scale.toFloat(),
                        0.5f,
                        minimumUiDensity = 2f,
                    )
                val pose = PoseStack()
                withFramebufferPixels(pose, metrics) {
                    for (x in listOf(0f, 0.5f, width / 2f, width - 0.5f, width.toFloat())) {
                        for (y in listOf(0f, 0.5f, height / 2f, height - 0.5f, height.toFloat())) {
                            val gui = pose.last().pose().transformPosition(x, y, 0f, Vector3f())
                            assertEquals(x, gui.x * scale, 0.001f, "$width x $height scale $scale x")
                            assertEquals(y, gui.y * scale, 0.001f, "$width x $height scale $scale y")
                            assertEquals(x, metrics.pixelX(x.toDouble() * metrics.guiWidth / width), 0.001f)
                            assertEquals(y, metrics.pixelY(y.toDouble() * metrics.guiHeight / height), 0.001f)
                        }
                    }
                }
                assertEquals(Matrix4f(), pose.last().pose())
                assertTrue(pose.clear())
            }
        }
    }

    @Test
    fun `CPU presentation restores the caller pose even when drawing fails`() {
        val metrics = ScreenMetrics(1927, 1447, 482, 362, 4f, 1f)
        val pose = PoseStack()
        pose.translate(7f, 11f, 0f)
        pose.scale(2f, 3f, 1f)
        val before = Matrix4f(pose.last().pose())
        assertFailsWith<IllegalStateException> {
            withFramebufferPixels(pose, metrics) {
                val expected = before.transformPosition(100f / 4, 200f / 4, 0f, Vector3f())
                val actual = pose.last().pose().transformPosition(100f, 200f, 0f, Vector3f())
                assertEquals(expected, actual)
                error("draw failed")
            }
        }
        assertEquals(before, pose.last().pose())
        assertTrue(pose.clear())
    }
}
