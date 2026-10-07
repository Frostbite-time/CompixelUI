package dev.compixel.ui.ore.overlay

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset

/**
 * The right-click menu of text fields in Ore's look: cut, copy, paste and the like, as Compose offers them for the
 * selection. [dev.compixel.ui.ore.theme.OreTheme] provides it; a design can provide its own instead.
 */
object OreTextContextMenu : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val point = with(LocalDensity.current) { DpOffset(status.rect.center.x.toDp(), status.rect.center.y.toDp()) }
        OreMenu(
            expanded = true,
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            items =
                items().mapIndexed { index, item -> OreMenuItem(index.toString(), item.label, onClick = item.onClick) },
            offset = point,
        )
    }
}
