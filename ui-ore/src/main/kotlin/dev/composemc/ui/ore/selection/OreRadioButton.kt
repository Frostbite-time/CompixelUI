package dev.composemc.ui.ore.selection

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.theme.LocalOreFeedback
import dev.composemc.ui.ore.theme.OreTheme
import kotlin.math.roundToInt

/** A controlled choice. Put related controls in a selectableGroup and keep the selected value in the caller. */
@Composable
fun OreRadioButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: String? = null) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val colors = OreTheme.colors
    val feedback = LocalOreFeedback.current
    Row(modifier.heightIn(min = 18.dp)
        .selectable(selected, interactions, indication = null, enabled = enabled, role = Role.RadioButton) {
            if (!selected) { onClick(); feedback.activate() }
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(17.dp).drawBehind {
            val face = when {
                !enabled -> colors.secondary
                selected && pressed -> colors.primaryPressed
                selected && hovered -> lerp(colors.primary, colors.text, .12f)
                selected -> colors.primary
                pressed -> colors.secondaryEdge
                hovered -> colors.secondaryHover
                else -> colors.switchTrack
            }
            val edge = when {
                !enabled -> colors.disabledEdge
                selected -> colors.primaryEdge
                else -> colors.frameEdge
            }
            val light = if (enabled) lerp(face, colors.text, .24f) else colors.mutedText
            // Rasterize the straight diamond edges on framebuffer pixels. The body
            // is 13dp; its separate focus diamond has a one-dp transparent gap.
            val unit = size.minDimension / 17f
            val cx = size.width / 2f; val cy = size.height / 2f
            fun span(radius: Float, y: Int): IntRange {
                val half = radius - kotlin.math.abs(y + .5f - cy)
                if (half < 0f) return IntRange.EMPTY
                return kotlin.math.ceil(cx - half - .5f).toInt()..kotlin.math.floor(cx + half - .5f).toInt()
            }
            fun strip(left: Int, right: Int, y: Int, color: Color) {
                if (right >= left) drawRect(color, Offset(left.toFloat(), y.toFloat()), Size((right-left+1).toFloat(), 1f))
            }
            fun diamond(radius: Float, color: Color, lower: Color = color) {
                for (y in 0 until size.height.roundToInt()) {
                    val row = span(radius, y)
                    if (!row.isEmpty()) strip(row.first, row.last, y, if (y + .5f > cy) lower else color)
                }
            }
            if (focused && enabled) {
                for (y in 0 until size.height.roundToInt()) {
                    val outer = span(8.5f * unit, y)
                    val inner = span(7.5f * unit, y)
                    if (outer.isEmpty()) continue
                    if (inner.isEmpty()) strip(outer.first, outer.last, y, colors.focus)
                    else {
                        strip(outer.first, inner.first-1, y, colors.focus)
                        strip(inner.last+1, outer.last, y, colors.focus)
                    }
                }
            }
            diamond(6.5f * unit, edge)
            diamond(5.5f * unit, light, edge)
            diamond(4.5f * unit, face)
            if (selected) diamond(2.5f * unit, if (enabled) colors.text else colors.secondaryButtonLightEdge)

        })
        if (label != null) OreText(label, color = if (enabled) colors.text else colors.mutedText)
    }
}
