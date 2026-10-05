package dev.compixel.ui.ore.selection

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.ore.theme.OreTheme
import kotlin.math.roundToInt

@Composable
fun OreSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val colors = OreTheme.colors
    val feedback = LocalUiFeedback.current
    val edge = if (enabled) colors.edge else colors.disabledEdge
    val thumbOffset by
        animateDpAsState(
            targetValue = if (checked) 13.dp else 0.dp,
            animationSpec = spring(dampingRatio = .78f, stiffness = Spring.StiffnessMedium),
            label = "OreSwitchThumbPosition",
        )
    val trackFill = if (!enabled) colors.secondary else if (checked) colors.primary else colors.switchTrack
    val trackHighlight = lerp(trackFill, colors.bevelLight, .20f)
    val thumbFill =
        when {
            !enabled -> colors.secondary
            pressed -> colors.secondaryPressed
            hovered -> colors.secondaryHover
            else -> colors.secondary
        }
    val thumbHighlight = lerp(thumbFill, colors.bevelLight, if (hovered) .80f else .40f)
    val thumbShadow = if (!enabled) colors.secondaryPressed else colors.secondaryEdge
    Box(
        modifier.size(28.dp, 15.dp).toggleable(
            checked,
            interactions,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
        ) {
            onCheckedChange(it)
            feedback.activate()
        }
    ) {
        // The recessed 13px track meets the 15px thumb edge-to-edge. It changes sides
        // immediately with the state while the thumb travels over it.
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = if (checked) 0.dp else 15.dp).size(13.dp).drawBehind {
                // OreUI's switch uses a one-GUI-pixel unit for both the recessed track and its glyphs.
                // Keeping this separate from the generic 1px pixel helper gives the control
                // the heavier profile of the original rather than a thin checkbox outline.
                val unit = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
                drawRect(edge)
                drawRect(
                    trackHighlight,
                    Offset(unit, unit),
                    Size((size.width - 2 * unit).coerceAtLeast(0f), (size.height - 2 * unit).coerceAtLeast(0f)),
                )
                drawRect(
                    trackFill,
                    Offset(2 * unit, 2 * unit),
                    Size((size.width - 4 * unit).coerceAtLeast(0f), (size.height - 4 * unit).coerceAtLeast(0f)),
                )
                val ink = if (!enabled) colors.disabledText else if (checked) colors.onPrimary else colors.onSecondary
                val left = (size.width / 2).roundToInt().toFloat() - 4 * unit
                val top = (size.height / 2).roundToInt().toFloat() - 4 * unit
                if (checked) {
                    drawRect(ink, Offset(left + 3.5f * unit, top + unit), Size(unit, 6 * unit))
                } else {
                    // The same crisp 8x8 icon geometry as OreUI's off glyph, not a generic
                    // stroked rectangle whose corners look oversized at Minecraft scales.
                    drawRect(ink, Offset(left + 2 * unit, top + unit), Size(4 * unit, unit))
                    drawRect(ink, Offset(left + 6 * unit, top + 2 * unit), Size(unit, 4 * unit))
                    drawRect(ink, Offset(left + unit, top + 2 * unit), Size(unit, 4 * unit))
                    drawRect(ink, Offset(left + 2 * unit, top + 6 * unit), Size(4 * unit, unit))
                }
            }
        )
        // This is the same two-step face and lower shadow used by the button, scaled to the
        // switch's 15px thumb. There is intentionally no post-click focus outline.
        Box(
            Modifier.align(Alignment.TopStart).offset(x = thumbOffset).size(15.dp).drawBehind {
                val p = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
                val frame = p
                val shadow = 2.dp.roundToPx().toFloat()
                val highlightSize =
                    Size(
                        (size.width - 2 * frame).coerceAtLeast(0f),
                        (size.height - 2 * frame - shadow).coerceAtLeast(0f),
                    )
                drawRect(edge)
                drawRect(thumbHighlight, Offset(frame, frame), highlightSize)
                drawRect(
                    thumbShadow,
                    Offset(frame, size.height - frame - shadow),
                    Size((size.width - 2 * frame).coerceAtLeast(0f), shadow),
                )
                drawRect(
                    thumbFill,
                    Offset(2 * frame, 2 * frame),
                    Size(
                        (size.width - 4 * frame).coerceAtLeast(0f),
                        (size.height - 4 * frame - shadow).coerceAtLeast(0f),
                    ),
                )
            }
        )
    }
}
