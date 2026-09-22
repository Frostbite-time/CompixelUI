package dev.composemc.ui.ore.overlay

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

internal class OrePopupPosition(private val point: IntOffset? = null, private val gap: Int = 3) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (point != null) anchorBounds.left + point.x else if (layoutDirection == LayoutDirection.Rtl) anchorBounds.right-popupContentSize.width else anchorBounds.left
        val below = if (point != null) anchorBounds.top + point.y else anchorBounds.bottom + gap
        val above = if (point != null) below-popupContentSize.height else anchorBounds.top-gap-popupContentSize.height
        return IntOffset(x.coerceIn(0, (windowSize.width-popupContentSize.width).coerceAtLeast(0)),
            (if (below+popupContentSize.height <= windowSize.height) below else above).coerceIn(0, (windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    }
}
