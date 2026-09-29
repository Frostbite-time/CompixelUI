package dev.compixel.ui.ore.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.theme.LocalOreFeedback
import dev.compixel.ui.ore.theme.OreTheme
import kotlin.math.roundToInt

@Composable
fun OreSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    require(
        value.isFinite() &&
            valueRange.start.isFinite() &&
            valueRange.endInclusive.isFinite() &&
            (valueRange.endInclusive - valueRange.start).isFinite() &&
            valueRange.endInclusive > valueRange.start
    ) {
        "Slider value and range must be finite, with a nonempty range"
    }
    val colors = OreTheme.colors
    val feedback = LocalOreFeedback.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val update by rememberUpdatedState(onValueChange)
    val span = valueRange.endInclusive - valueRange.start
    // Shared thumb treatment with the switch: same 1px frame, light inner ring
    // and lower shadow construction. Geometry stays local to each control.
    // The slider never takes keyboard focus: no focus ring, no arrow-key input.
    // It stays operable through pointer drag, semantics actions and its
    // onValueChange callback, so removing focus changes no input path.
    val thumb = 15.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                if (!enabled) disabled()
                setProgress {
                    if (enabled) {
                        update(it.coerceIn(valueRange))
                        true
                    } else false
                }
            }
            .hoverable(interactions, enabled)
            .pointerInput(enabled, valueRange) {
                if (enabled)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun move(x: Float) {
                            update(
                                (valueRange.start +
                                        ((x - thumb.toPx() / 2) / (size.width - thumb.toPx()).coerceAtLeast(1f))
                                            .coerceIn(0f, 1f) * span)
                                    .coerceIn(valueRange)
                            )
                        }
                        dragging = true
                        try {
                            move(down.position.x)
                            down.consume()
                            do {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                move(change.position.x)
                                change.consume()
                            } while (change.pressed)
                        } finally {
                            dragging = false
                        }
                        feedback.activate()
                    }
            }
            .drawBehind {
                val p = 1.dp.roundToPx().coerceAtLeast(1).toFloat()
                val thumbPx = thumb.roundToPx().toFloat()
                val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
                val center = (fraction * (size.width - thumbPx).coerceAtLeast(0f)).roundToInt().toFloat()
                val trackHeight = 6.dp.roundToPx().toFloat()
                val trackTop = (size.height / 2 - trackHeight / 2).roundToInt().toFloat()
                val thumbTop = (size.height / 2 - thumbPx / 2).roundToInt().toFloat()
                val shadow = 2.dp.roundToPx().toFloat()
                val edge = if (enabled) colors.edge else colors.disabledEdge
                val trackFilled = if (enabled) colors.trackFilled else colors.highlight
                val trackFilledLight = if (enabled) colors.trackFilledLight else colors.highlight
                val trackEmpty = if (enabled) colors.trackEmpty else colors.highlight
                val trackEmptyLight = if (enabled) colors.trackEmptyLight else colors.highlight
                fun track(left: Float, right: Float, light: Color, fill: Color) {
                    drawRect(edge, Offset(left, trackTop), Size((right - left).coerceAtLeast(0f), trackHeight))
                    drawRect(
                        light,
                        Offset(left + p, trackTop + p),
                        Size((right - left - 2 * p).coerceAtLeast(0f), trackHeight - 2 * p),
                    )
                    drawRect(
                        fill,
                        Offset(left + 2 * p, trackTop + 2 * p),
                        Size((right - left - 4 * p).coerceAtLeast(0f), trackHeight - 4 * p),
                    )
                }
                track(0f, center + thumbPx / 2, trackFilledLight, trackFilled)
                track(center + thumbPx / 2, size.width, trackEmptyLight, trackEmpty)
                val pressed = dragging
                val thumbFill =
                    when {
                        !enabled -> colors.secondary
                        pressed -> colors.secondaryPressed
                        hovered -> colors.secondaryHover
                        else -> colors.secondary
                    }
                val thumbHighlight = lerp(thumbFill, colors.bevelLight, if (hovered) .80f else .40f)
                val thumbShadow = if (!enabled) colors.secondaryPressed else colors.secondaryEdge
                // Same face as the switch thumb: 1px frame, 1px light ring on three
                // sides, 2px face inset, lower shadow. Geometry stays local here.
                val frame = p
                val highlightSize =
                    Size((thumbPx - 2 * frame).coerceAtLeast(0f), (thumbPx - 2 * frame - shadow).coerceAtLeast(0f))
                drawRect(edge, Offset(center, thumbTop), Size(thumbPx, thumbPx))
                drawRect(thumbHighlight, Offset(center + frame, thumbTop + frame), highlightSize)
                drawRect(
                    thumbShadow,
                    Offset(center + frame, thumbTop + thumbPx - frame - shadow),
                    Size((thumbPx - 2 * frame).coerceAtLeast(0f), shadow),
                )
                drawRect(
                    thumbFill,
                    Offset(center + 2 * frame, thumbTop + 2 * frame),
                    Size((thumbPx - 4 * frame).coerceAtLeast(0f), (thumbPx - 4 * frame - shadow).coerceAtLeast(0f)),
                )
            }
    )
}
