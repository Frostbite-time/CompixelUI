package dev.composemc.testing.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Asymmetric transparent fixture: detects vertical flips, alpha errors, clipping and glyph corruption. */
@Composable
fun RendererFixture() {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFFFFC857), Offset(4f, 4f), Size(20f, 12f))
            drawRect(Color(0x80EF5350), Offset(20f, 28f), Size(150f, 100f))
            clipRect(55f, 45f, 255f, 165f) {
                rotate(23f, Offset(165f, 105f)) {
                    drawRect(Color(0xA04FC3F7), Offset(80f, 38f), Size(170f, 100f))
                }
            }
            drawCircle(Color(0xC080CBC4), 34f, Offset(272f, 62f))
            drawRect(Color(0xFFAB47BC), Offset(280f, 181f), Size(32f, 24f))
        }
        BasicText(
            "Skia / Compose 012345",
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            style = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace),
        )
    }
}
