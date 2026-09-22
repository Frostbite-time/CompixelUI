package dev.composemc.ui.ore.inventory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.theme.OreTheme
import kotlin.math.roundToInt

/**
 * An 18 × 18 inventory well: a 16 × 16 content area surrounded by one-pixel
 * recessed edges. Content, resource identity and interaction are supplied by the host.
 */
@Composable
fun OreSlot(
    modifier: Modifier = Modifier, marked: Boolean = false, highlighted: Boolean = false,
    contentModifier: Modifier = Modifier.size(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = OreTheme.colors
    val well = if (marked) colors.markedSlot else colors.slot
    val fill = if (highlighted) lerp(well, colors.slotHover, .7f) else well
    val outline = if (!highlighted) null else if (marked) lerp(colors.slotHoverEdge, colors.primary, .32f)
    else colors.slotHoverEdge
    Box(
        modifier.defaultMinSize(18.dp, 18.dp)
            .oreSlotFrame(
                fill,
                if (marked) colors.edge else colors.slotEdge,
                if (marked) lerp(colors.highlight, colors.primary, .35f) else colors.highlight,
                outline = outline,
            )
            .oreSlotContentClip()
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) { Box(contentModifier, contentAlignment = Alignment.Center, content = content) }
}

/**
 * Vanilla-style recessed slot geometry. The dark upper/left and light lower/right
 * edges meet through the well colour at top-right and bottom-left; the bright
 * bottom-right corner is never wrapped by an additional dark outline.
 */
private fun Modifier.oreSlotFrame(fill: Color, dark: Color, light: Color, outline: Color?): Modifier = drawBehind {
    val edge = slotEdge(size)
    val horizontal = (size.width - edge).coerceAtLeast(0f)
    val vertical = (size.height - edge).coerceAtLeast(0f)
    drawRect(fill)
    if (outline != null) {
        drawRect(outline)
        drawRect(fill, Offset(edge, edge), Size(
            (size.width - edge * 2).coerceAtLeast(0f),
            (size.height - edge * 2).coerceAtLeast(0f),
        ))
        return@drawBehind
    }
    // Leave the top-right and bottom-left cells as `fill`: each is one
    // template pixel, scaled with the 18 × 18 slot rather than a gradient.
    drawRect(dark, Offset.Zero, Size(horizontal, edge))
    drawRect(dark, Offset.Zero, Size(edge, vertical))
    drawRect(light, Offset(edge, size.height - edge), Size(horizontal, edge))
    drawRect(light, Offset(size.width - edge, edge), Size(edge, vertical))
}

/** Keep enlarged slot content inside the same scaled 16 × 16 well. */
private fun Modifier.oreSlotContentClip(): Modifier = drawWithContent {
    val edge = slotEdge(size)
    if (size.width <= edge * 2 || size.height <= edge * 2) drawContent()
    else {
        val contentScope = this
        clipRect(edge, edge, size.width - edge, size.height - edge) { contentScope.drawContent() }
    }
}

/** One edge template pixel is one eighteenth of the rendered slot side. */
private fun slotEdge(size: Size): Float = (minOf(size.width, size.height) / 18f)
    .roundToInt().coerceAtLeast(1).toFloat().coerceAtMost(minOf(size.width, size.height) / 2)
