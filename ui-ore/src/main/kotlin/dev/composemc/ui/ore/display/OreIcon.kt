package dev.composemc.ui.ore.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.theme.LocalOreContentColor
import kotlin.math.roundToInt

/** Bundled pixel glyphs. Custom visuals can use OreIconButton's composable icon slot. */
enum class OreGlyph { Close, Search, Edit, Network, Check, ArrowRight, Back, Settings }

/** Original pixel geometry; no Minecraft or Bedrock assets are redistributed. */
@Composable
fun OreIcon(glyph: OreGlyph, modifier: Modifier = Modifier, color: Color = LocalOreContentColor.current) {
    Canvas(modifier.size(8.dp)) {
        fun rect(x: Int, y: Int, w: Int, h: Int) {
            val x1 = (x * size.width / 16).roundToInt().toFloat()
            val y1 = (y * size.height / 16).roundToInt().toFloat()
            val x2 = ((x + w) * size.width / 16).roundToInt().toFloat()
            val y2 = ((y + h) * size.height / 16).roundToInt().toFloat()
            drawRect(color, Offset(x1, y1), Size(x2 - x1, y2 - y1))
        }
        when (glyph) {
            OreGlyph.Close -> repeat(10) { rect(3 + it, 3 + it, 2, 2); rect(12 - it, 3 + it, 2, 2) }
            OreGlyph.Check -> { repeat(4) { rect(2 + it, 7 + it, 2, 2) }; repeat(7) { rect(5 + it, 10 - it, 2, 2) } }
            OreGlyph.Search -> {
                rect(4, 2, 6, 2); rect(2, 4, 2, 6); rect(4, 10, 6, 2); rect(10, 4, 2, 6)
                repeat(4) { rect(10 + it, 10 + it, 2, 2) }
            }
            OreGlyph.Edit -> { repeat(8) { rect(3 + it, 10 - it, 3, 3) }; rect(2, 12, 3, 2); rect(11, 1, 3, 2) }
            OreGlyph.Network -> {
                rect(6, 1, 4, 4); rect(1, 11, 4, 4); rect(11, 11, 4, 4)
                rect(7, 5, 2, 4); rect(2, 8, 12, 2); rect(2, 8, 2, 3); rect(12, 8, 2, 3)
            }
            OreGlyph.ArrowRight, OreGlyph.Back -> {
                rect(2, 7, 11, 2)
                repeat(6) { val x = if (glyph == OreGlyph.ArrowRight) 8 + it else 6 - it; rect(x, 2 + it, 2, 2); rect(x, 12 - it, 2, 2) }
            }
            OreGlyph.Settings -> { rect(2, 4, 12, 2); rect(2, 10, 12, 2); rect(5, 2, 2, 6); rect(10, 8, 2, 6) }
        }
    }
}
