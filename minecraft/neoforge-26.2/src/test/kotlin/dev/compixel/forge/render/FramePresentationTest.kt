package dev.compixel.forge.render

import kotlin.test.*
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix3x2f
import org.joml.Vector2f
import org.junit.jupiter.api.Test

class FramePresentationTest {
    private fun blit(
        metrics: ScreenMetrics,
        pose: Matrix3x2f = Matrix3x2f(),
        scissor: ScreenRectangle? = null,
        flipY: Boolean = false,
    ) = frameBlitRenderState(metrics, pose, RenderPipelines.GUI_TEXTURED, TextureSetup.noTexture(), scissor, flipY)

    @Test
    fun `odd windows keep texel centers and edges at their framebuffer positions`() {
        val sizes = listOf(1927 to 1447, 2560 to 1440, 3840 to 2160, 1366 to 768, 1920 to 1080)
        for (scale in 1..9) {
            // Include every possible rounding remainder in each axis.
            val windows = sizes + (0 until scale).map { 960 + it to 720 + (scale - 1 - it) }
            for ((width, height) in windows) {
                val metrics = ScreenMetrics(
                    width, height, (width + scale - 1) / scale, (height + scale - 1) / scale,
                    scale.toFloat(), 0.5f, minimumUiDensity = 2f,
                )
                val state = blit(metrics)
                for (x in listOf(0f, 0.5f, width / 2f, width - 0.5f, width.toFloat())) {
                    for (y in listOf(0f, 0.5f, height / 2f, height - 0.5f, height.toFloat())) {
                        // Interpolate the submitted quad at a source texel, then apply Minecraft's exact projection.
                        val u = x / width
                        val v = y / height
                        val localX = state.x0() + (u - state.u0()) / (state.u1() - state.u0()) * (state.x1() - state.x0())
                        val localY = state.y0() + (v - state.v0()) / (state.v1() - state.v0()) * (state.y1() - state.y0())
                        val gui = state.pose().transformPosition(localX, localY, Vector2f())
                        assertEquals(x, gui.x * scale, 0.001f, "$width x $height scale $scale x")
                        assertEquals(y, gui.y * scale, 0.001f, "$width x $height scale $scale y")
                        // Minecraft mouse events use rounded GUI dimensions, unlike the rendering projection.
                        assertEquals(x, metrics.pixelX(x.toDouble() * metrics.guiWidth / width), 0.001f)
                        assertEquals(y, metrics.pixelY(y.toDouble() * metrics.guiHeight / height), 0.001f)
                    }
                }
            }
        }
    }

    @Test
    fun `deferred presentation preserves the caller pose and GUI scissor`() {
        val metrics = ScreenMetrics(1927, 1447, 482, 362, 4f, 1f)
        val pose = Matrix3x2f().translate(7f, 11f).scale(2f, 3f)
        val before = Matrix3x2f(pose)
        val scissor = ScreenRectangle(20, 30, 100, 80)
        val state = blit(metrics, pose, scissor)
        assertEquals(before, pose)
        assertSame(scissor, state.scissorArea())
        assertSame(RenderPipelines.GUI_TEXTURED, state.pipeline())
        assertSame(TextureSetup.noTexture(), state.textureSetup())
        val expected = before.transformPosition(100f / 4, 200f / 4, Vector2f())
        val actual = state.pose().transformPosition(100f, 200f, Vector2f())
        assertEquals(expected, actual)
        val saved = Matrix3x2f(state.pose())
        pose.identity()
        assertEquals(saved, state.pose())
    }

    @Test
    fun `OpenGL orientation changes UVs without moving the frame`() {
        val metrics = ScreenMetrics(1927, 1447, 482, 362, 4f, 1f)
        val upright = blit(metrics)
        val flipped = blit(metrics, flipY = true)
        assertEquals(upright.pose(), flipped.pose())
        assertEquals(upright.x1(), flipped.x1())
        assertEquals(upright.y1(), flipped.y1())
        assertEquals(0f, flipped.u0())
        assertEquals(1f, flipped.u1())
        assertEquals(0f, upright.v0())
        assertEquals(1f, upright.v1())
        assertEquals(1f, flipped.v0())
        assertEquals(0f, flipped.v1())
    }
}
