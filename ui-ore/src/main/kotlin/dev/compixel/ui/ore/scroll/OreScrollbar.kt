@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.compixel.ui.ore.scroll

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme
import kotlin.math.max
import kotlinx.coroutines.launch

@Composable
fun OreScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbLength: Dp? = null,
    minimumThumbLength: Dp = 15.dp,
) = OreScrollbar(state.oreScrollbarMetrics(), modifier, enabled, thumbLength, minimumThumbLength)

@Composable
fun OreScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbLength: Dp? = null,
    minimumThumbLength: Dp = 15.dp,
) = OreScrollbar(state.oreScrollbarMetrics(), modifier, enabled, thumbLength, minimumThumbLength)

internal data class OreScrollbarMetrics(
    val visibleFraction: Float,
    val positionFraction: Float,
    val canScroll: Boolean,
    val scrollToFraction: suspend (Float) -> Unit,
)

@Composable
internal fun LazyListState.oreScrollbarMetrics(): OreScrollbarMetrics =
    rememberScrollbarAdapter(this).oreScrollbarMetrics()

@Composable
internal fun ScrollState.oreScrollbarMetrics(): OreScrollbarMetrics =
    rememberScrollbarAdapter(this).oreScrollbarMetrics()

@Composable
private fun ScrollbarAdapter.oreScrollbarMetrics(): OreScrollbarMetrics {
    val content = contentSize.coerceAtLeast(0.0)
    val viewport = viewportSize.coerceAtLeast(0.0)
    val maximum = (content - viewport).coerceAtLeast(0.0)
    val canScroll = maximum > 0.0 && viewport > 0.0
    return OreScrollbarMetrics(
        visibleFraction = if (content > 0.0) (viewport / content).toFloat().coerceIn(0f, 1f) else 1f,
        positionFraction = if (canScroll) (scrollOffset / maximum).toFloat().coerceIn(0f, 1f) else 0f,
        canScroll = canScroll,
        scrollToFraction = { fraction -> scrollTo(fraction.coerceIn(0f, 1f) * maximum) },
    )
}

/**
 * An Ore-styled vertical scrollbar. [thumbLength] pins the thumb to an explicit length; when it is null, the thumb
 * represents the visible fraction and is at least [minimumThumbLength] tall.
 */
@Composable
internal fun OreScrollbar(
    metrics: OreScrollbarMetrics,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbLength: Dp? = null,
    minimumThumbLength: Dp = 15.dp,
) {
    require(minimumThumbLength > 0.dp) { "minimumThumbLength must be positive" }
    require(thumbLength == null || thumbLength > 0.dp) { "thumbLength must be positive" }

    val colors = OreTheme.colors
    val callback by rememberUpdatedState(metrics.scrollToFraction)
    val visibleFraction by rememberUpdatedState(metrics.visibleFraction)
    val scrollScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val requestedThumbLength = with(density) { thumbLength?.toPx() }
    val minimumThumbLengthPx = with(density) { minimumThumbLength.toPx() }
    val scrollable = enabled && metrics.canScroll
    var pointerY by remember { mutableStateOf<Float?>(null) }

    fun resolvedThumbLength(trackHeight: Float): Float {
        // A disabled rail still needs a recognisable thumb at its fixed top position;
        // it must not turn into a thumb that fills and conceals the whole rail.
        val requested =
            requestedThumbLength
                ?: if (scrollable) {
                    max(trackHeight * visibleFraction, minimumThumbLengthPx)
                } else {
                    minimumThumbLengthPx
                }
        return requested.coerceIn(0f, trackHeight.coerceAtLeast(0f))
    }

    Box(
        modifier
            // The 4dp rail is centred within the 6dp-wide thumb, whose 2dp face needs
            // one black pixel and one bright pixel on each side.
            .width(6.dp)
            .onPointerEvent(PointerEventType.Enter) { pointerY = it.changes.firstOrNull()?.position?.y }
            .onPointerEvent(PointerEventType.Move) { pointerY = it.changes.firstOrNull()?.position?.y }
            .onPointerEvent(PointerEventType.Exit) { pointerY = null }
            // Do not key this handler to scroll position. Moving the thumb recomposes the
            // canvas, but replacing an active pointer-input coroutine would cancel a drag.
            .pointerInput(scrollable, requestedThumbLength, minimumThumbLengthPx) {
                if (scrollable)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun move(y: Float) {
                            val trackHeight = size.height.toFloat()
                            val thumb = resolvedThumbLength(trackHeight)
                            val fraction = ((y - thumb / 2f) / (trackHeight - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            scrollScope.launch { callback(fraction) }
                        }
                        move(down.position.y)
                        down.consume()
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            move(change.position.y)
                            change.consume()
                        } while (change.pressed)
                    }
            }
            .drawBehind {
                val pixel = 1.dp.toPx().coerceAtLeast(1f)
                val trackWidth = 4f * pixel
                val trackLeft = ((size.width - trackWidth) / 2f).coerceAtLeast(0f)
                val trackEdge = if (scrollable) colors[OreColors.edge] else colors[OreColors.disabledEdge]
                val rail = if (scrollable) colors[OreColors.trackEmptyLight] else colors[OreColors.highlight]
                // A plain, recessed 2dp light-grey rail inside its 1dp black frame.
                drawRect(trackEdge, Offset(trackLeft, 0f), Size(trackWidth.coerceAtMost(size.width), size.height))
                drawRect(
                    rail,
                    Offset(trackLeft + pixel, pixel),
                    Size(
                        (trackWidth - 2f * pixel).coerceAtLeast(0f),
                        (size.height - 2f * pixel).coerceAtLeast(0f),
                    ),
                )

                val thumbHeight = resolvedThumbLength(size.height)
                val thumbTop =
                    if (scrollable) {
                        (size.height - thumbHeight).coerceAtLeast(0f) * metrics.positionFraction
                    } else {
                        0f
                    }
                val thumbHovered =
                    scrollable && pointerY?.let { it >= thumbTop && it <= thumbTop + thumbHeight } == true
                val thumbEdge = if (scrollable) colors[OreColors.buttonBorder] else colors[OreColors.disabledEdge]
                val thumbLight =
                    if (scrollable && thumbHovered) colors[OreColors.secondaryButtonCorner]
                    else if (scrollable) colors[OreColors.secondaryButtonLightEdge] else colors[OreColors.highlight]
                val thumbFace =
                    if (scrollable && thumbHovered) colors[OreColors.secondaryHover]
                    else if (scrollable) colors[OreColors.secondary] else colors[OreColors.raised]
                val thumbLedge = if (scrollable) colors[OreColors.secondaryEdge] else colors[OreColors.disabledText]
                val ledgeHeight = (2.dp.toPx()).coerceAtMost((thumbHeight - 2f * pixel).coerceAtLeast(0f))
                val brightHeight = (thumbHeight - 2f * pixel - ledgeHeight).coerceAtLeast(0f)
                val faceHeight = (thumbHeight - 4f * pixel - ledgeHeight).coerceAtLeast(0f)

                // The thumb copies the Ore secondary-control construction: 1dp black
                // casing, 1dp bright inner edge, a 2dp inset face and a 2dp lower ledge.
                drawRect(thumbEdge, Offset(0f, thumbTop), Size(size.width, thumbHeight))
                drawRect(
                    thumbLight,
                    Offset(pixel, thumbTop + pixel),
                    Size(
                        (size.width - 2f * pixel).coerceAtLeast(0f),
                        brightHeight,
                    ),
                )
                drawRect(
                    thumbLedge,
                    Offset(pixel, thumbTop + thumbHeight - pixel - ledgeHeight),
                    Size(
                        (size.width - 2f * pixel).coerceAtLeast(0f),
                        ledgeHeight,
                    ),
                )
                drawRect(
                    thumbFace,
                    Offset(2f * pixel, thumbTop + 2f * pixel),
                    Size(
                        (size.width - 4f * pixel).coerceAtLeast(0f),
                        faceHeight,
                    ),
                )
            }
    )
}
