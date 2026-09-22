package dev.composemc.ui.ore.display

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.theme.OreTheme
import kotlin.math.roundToInt

@Composable
fun OreProgressBar(progress: Float, modifier: Modifier = Modifier) {
    require(progress.isFinite()) { "Progress must be finite" }
    val colors = OreTheme.colors
    Box(modifier.fillMaxWidth().height(3.dp).drawBehind {
        val p = 1.dp.roundToPx().coerceAtLeast(1).toFloat()
        val width = (size.width - 2 * p).coerceAtLeast(0f)
        val height = (size.height - 2 * p).coerceAtLeast(0f)
        val filled = (width * progress.coerceIn(0f, 1f)).roundToInt().toFloat()
        drawRect(colors.edge)
        drawRect(colors.panel, Offset(p, p), Size(width, height))
        drawRect(colors.primary, Offset(p, p), Size(filled, height))
        drawRect(colors.primaryHover, Offset(p, p), Size(filled, minOf(p, height)))
    })
}
