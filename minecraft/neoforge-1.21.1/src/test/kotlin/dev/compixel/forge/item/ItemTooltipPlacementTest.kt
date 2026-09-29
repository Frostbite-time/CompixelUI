package dev.compixel.forge.item

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ItemTooltipPlacementTest {
    @Test
    fun interiorTooltipKeepsItsCursorGap() {
        assertEquals(
            IntOffset(112, 112),
            placeItemTooltip(IntOffset(100, 100), IntSize(1000, 720), IntSize(180, 160), 12, 4),
        )
    }

    @Test
    fun bottomRightTooltipFlipsAwayFromTheHoveredItem() {
        assertEquals(
            IntOffset(776, 516),
            placeItemTooltip(IntOffset(968, 688), IntSize(1000, 720), IntSize(180, 160), 12, 4),
        )
    }

    @Test
    fun nearlyFullWindowTooltipReducesItsMarginWithoutOverflow() {
        assertEquals(IntOffset(2, 2), placeItemTooltip(IntOffset(50, 50), IntSize(100, 100), IntSize(96, 96), 12, 4))
        assertEquals(IntOffset.Zero, placeItemTooltip(IntOffset(0, 0), IntSize(100, 100), IntSize(100, 100), 12, 4))
    }
}
