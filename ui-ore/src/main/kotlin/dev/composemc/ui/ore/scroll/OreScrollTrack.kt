package dev.composemc.ui.ore.scroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scroll track for remotely paged or virtualized data without a Compose LazyListState. */
@Composable
fun OreScrollTrack(
    position: Int,
    maximum: Int,
    visibleFraction: Float,
    onPositionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbLength: Dp? = null,
    minimumThumbLength: Dp = 15.dp,
) {
    require(visibleFraction.isFinite()) { "visibleFraction must be finite" }
    val callback by rememberUpdatedState(onPositionChange)
    val maxPosition = maximum.coerceAtLeast(0)
    val fraction = visibleFraction.coerceIn(0f, 1f)
    OreScrollbar(
        metrics =
            OreScrollbarMetrics(
                visibleFraction = fraction,
                positionFraction =
                    if (maxPosition > 0) position.coerceIn(0, maxPosition).toFloat() / maxPosition else 0f,
                canScroll = maxPosition > 0 && fraction < 1f,
                scrollToFraction = { ratio -> callback((ratio * maxPosition).toInt().coerceIn(0, maxPosition)) },
            ),
        modifier = modifier,
        enabled = enabled,
        thumbLength = thumbLength,
        minimumThumbLength = minimumThumbLength,
    )
}
