package dev.compixel.testing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.compixel.bridge.ComposeThread
import dev.compixel.host.UiSession
import dev.compixel.platform.Viewport
import dev.compixel.ui.LocalOverlayVisibility
import dev.compixel.ui.OverlayVisibility
import dev.compixel.ui.ore.overlay.OreDialog
import dev.compixel.ui.ore.theme.OreColors
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test

class OreOverlayTest {
    @Test
    fun `a dialog fades its panel and scrim with the screen, and the scrim never darkens what shows through it`() {
        val visibility = ComposeThread.call { mutableFloatStateOf(1f) }
        val overlays =
            object : OverlayVisibility {
                @Composable override fun animate(): State<Float> = visibility
            }
        var content = Rect.Zero
        UiSession(Viewport(200, 160)) {
                CompositionLocalProvider(LocalOverlayVisibility provides overlays) {
                    // The screen draws its left half; the right half shows what lies beneath it, such as the game.
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.5f).background(Color.White))
                    OreDialog("Fade", {}, buttons = {}) {
                        Box(Modifier.size(12.dp).onGloballyPositioned { content = it.boundsInWindow() })
                    }
                }
            }
            .use { session ->
                Surface.makeRasterN32Premul(200, 160).use { surface ->
                    var time = 1_000_000_000L
                    fun render(shown: Float): BufferedImage {
                        ComposeThread.call { visibility.floatValue = shown }
                        repeat(3) {
                            time += 20_000_000L
                            session.frame(time)?.use {
                                surface.canvas.clear(0)
                                it.draw(surface.canvas)
                            }
                        }
                        return surface.makeImageSnapshot().use { image ->
                            image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
                        }
                    }
                    val panel = OreColors().panel
                    for (shown in listOf(1f, 0.5f, 0f)) {
                        val image = render(shown)
                        val inside = ComposeThread.call { content }
                        assertTrue(inside.width > 0, "The dialog did not lay out")
                        // Compose's scrim, 60% black, blends onto the screen beneath with the screen's visibility.
                        val screen = 255f * (1f - 0.6f * shown)
                        assertGray(screen, image.getRGB(50, 5), "scrim over the screen at $shown")
                        assertEquals(0, image.getRGB(150, 5) ushr 24, "scrim over the game at $shown")
                        val expected =
                            Color(
                                red = panel.red * shown + screen / 255f * (1f - shown),
                                green = panel.green * shown + screen / 255f * (1f - shown),
                                blue = panel.blue * shown + screen / 255f * (1f - shown),
                            )
                        assertNear(
                            expected.toArgb(),
                            image.getRGB(inside.center.x.roundToInt(), inside.center.y.roundToInt()),
                            "panel at $shown",
                        )
                    }
                }
            }
    }

    private fun assertGray(value: Float, argb: Int, what: String) {
        val gray = value.roundToInt()
        assertNear(Color(gray, gray, gray).toArgb(), argb, what)
    }

    private fun assertNear(expected: Int, actual: Int, what: String) {
        val close =
            (0..24 step 8).all { shift -> abs((expected ushr shift and 0xFF) - (actual ushr shift and 0xFF)) <= 3 }
        assertTrue(close, "$what: expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}")
    }
}
