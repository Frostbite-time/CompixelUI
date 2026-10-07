package dev.compixel.ui.ore.internal

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.theme.ColorValues
import kotlin.math.roundToInt

/** Shared casing, lower ledge and face translation for buttons and joined choice segments. */
internal fun Modifier.oreButtonFrame(
    colors: ColorValues,
    style: OreButtonStyle,
    enabled: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    depressed: Boolean = pressed,
    focused: Boolean = false,
    selectedMark: Boolean = false,
): Modifier {
    val fill =
        when {
            !enabled -> colors[OreColors.secondary]
            pressed && style == OreButtonStyle.Primary -> colors[OreColors.primaryPressed]
            pressed && style == OreButtonStyle.Secondary -> colors[OreColors.secondaryButtonActive]
            pressed && style == OreButtonStyle.Destructive -> colors[OreColors.dangerPressed]
            pressed -> colors[OreColors.panel]
            // Hover changes only the face's two tone values.  Its casing and lower ledge remain
            // untouched, so the button keeps the same physical form as at rest.
            hovered && style == OreButtonStyle.Primary -> colors[OreColors.primaryHover]
            hovered && style == OreButtonStyle.Secondary -> colors[OreColors.secondaryButtonActive]
            hovered && style == OreButtonStyle.Destructive -> colors[OreColors.dangerHover]
            hovered -> colors[OreColors.panel]
            style == OreButtonStyle.Primary -> colors[OreColors.primary]
            style == OreButtonStyle.Secondary -> colors[OreColors.secondary]
            style == OreButtonStyle.Destructive -> colors[OreColors.danger]
            else -> colors[OreColors.raised]
        }
    val bottom =
        when (style) {
            OreButtonStyle.Primary -> colors[OreColors.primaryEdge]
            OreButtonStyle.Secondary -> colors[OreColors.secondaryEdge]
            OreButtonStyle.Destructive -> colors[OreColors.dangerEdge]
            else -> colors[OreColors.edge]
        }
    return drawBehind {
        val p = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val frame = p
        val ledge = 2.dp.roundToPx().toFloat()
        // A pressed button is a solid object moving down into the space previously used
        // by its lower ledge; do not merely slide the face within a fixed casing.
        val bodyTop = if (depressed) ledge else 0f
        val bodyWidth = size.width
        val bodyHeight = (size.height - bodyTop).coerceAtLeast(0f)
        val innerWidth = (bodyWidth - frame * 2).coerceAtLeast(0f)
        val innerHeight = (bodyHeight - frame * 2).coerceAtLeast(0f)
        val faceHeight = if (depressed) innerHeight else (innerHeight - ledge).coerceAtLeast(0f)
        val bodyOffset = Offset(0f, bodyTop)
        val innerOffset = Offset(frame, bodyTop + frame)
        drawRect(
            if (enabled) colors[OreColors.buttonBorder] else colors[OreColors.disabledEdge],
            bodyOffset,
            Size(bodyWidth, bodyHeight),
        )
        drawRect(if (enabled) bottom else colors[OreColors.disabledEdge], innerOffset, Size(innerWidth, innerHeight))
        if (style == OreButtonStyle.Secondary && enabled) {
            // Fixed two-pixel face construction.  Hover and press only darken `fill`;
            // the bevel and its two transition corners deliberately never change.
            drawRect(fill, innerOffset, Size(innerWidth, faceHeight))
            drawRect(colors[OreColors.secondaryButtonLightEdge], innerOffset, Size(innerWidth, frame))
            drawRect(
                colors[OreColors.secondaryButtonLightEdge],
                Offset(innerOffset.x, innerOffset.y + frame),
                Size(frame, (faceHeight - frame).coerceAtLeast(0f)),
            )
            drawRect(
                colors[OreColors.secondaryButtonDarkEdge],
                Offset(innerOffset.x + frame, innerOffset.y + faceHeight - frame),
                Size((innerWidth - frame).coerceAtLeast(0f), frame),
            )
            drawRect(
                colors[OreColors.secondaryButtonDarkEdge],
                Offset(innerOffset.x + innerWidth - frame, innerOffset.y + frame),
                Size(frame, (faceHeight - frame).coerceAtLeast(0f)),
            )
            drawRect(
                colors[OreColors.secondaryButtonCorner],
                Offset(innerOffset.x + innerWidth - frame, innerOffset.y),
                Size(frame, frame),
            )
            drawRect(
                colors[OreColors.secondaryButtonCorner],
                Offset(innerOffset.x, innerOffset.y + faceHeight - frame),
                Size(frame, frame),
            )
        } else {
            val highlightAmount =
                when (style) {
                    OreButtonStyle.Primary -> if (depressed) .10f else .20f
                    OreButtonStyle.Secondary -> .40f
                    OreButtonStyle.Destructive -> .10f
                    OreButtonStyle.Quiet -> .20f
                }
            val faceHighlight = lerp(fill, colors[OreColors.bevelLight], highlightAmount)
            drawRect(faceHighlight, innerOffset, Size(innerWidth, faceHeight))
            drawRect(
                fill,
                Offset(innerOffset.x + frame, innerOffset.y + frame),
                Size((innerWidth - 2 * frame).coerceAtLeast(0f), (faceHeight - 2 * frame).coerceAtLeast(0f)),
            )
        }

        if (focused && enabled) {
            drawRect(
                colors[OreColors.focus],
                Offset(frame / 2, bodyTop + frame / 2),
                Size((bodyWidth - frame).coerceAtLeast(0f), (bodyHeight - frame).coerceAtLeast(0f)),
                style = Stroke(frame),
            )
        }
        if (selectedMark) {
            val left = (size.width * .38f).roundToInt().toFloat()
            val right = (size.width * .62f).roundToInt().toFloat()
            drawRect(
                if (enabled) colors[OreColors.onPrimary] else colors[OreColors.disabledText],
                Offset(left, size.height - 2 * frame),
                Size(right - left, frame),
            )
        }
    }
}

internal fun Modifier.oreButtonContentOffset(depressed: Boolean): Modifier = offset {
    val drop = 2.dp.roundToPx()
    IntOffset(0, -(drop + 1) / 2 + if (depressed) drop else 0)
}
