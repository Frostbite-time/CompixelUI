@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.compixel.forge.item

import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider

internal object ItemTooltipPlacement : TooltipPlacement {
    @Composable
    override fun positionProvider(cursorPosition: Offset): PopupPositionProvider {
        val density = LocalDensity.current
        val gap = with(density) { 6.dp.roundToPx() }
        val margin = with(density) { 2.dp.roundToPx() }
        return remember(cursorPosition, gap, margin) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset =
                    placeItemTooltip(
                        anchorBounds.topLeft + cursorPosition.round(),
                        windowSize,
                        popupContentSize,
                        gap,
                        margin,
                    )
            }
        }
    }
}

/** Prefer the cursor's lower right, then flip before clamping to keep the hovered item clear. */
internal fun placeItemTooltip(cursor: IntOffset, window: IntSize, tooltip: IntSize, gap: Int, margin: Int): IntOffset {
    fun place(pointer: Int, extent: Int, size: Int): Int {
        val inset = minOf(margin, ((extent - size) / 2).coerceAtLeast(0))
        val after = pointer + gap
        val preferred = if (after + size <= extent - inset) after else pointer - gap - size
        return preferred.coerceIn(inset, (extent - inset - size).coerceAtLeast(inset))
    }
    return IntOffset(place(cursor.x, window.width, tooltip.width), place(cursor.y, window.height, tooltip.height))
}
