package dev.compixel.ui.ore.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.internal.oreFrame
import dev.compixel.ui.ore.internal.oreOutline
import dev.compixel.ui.ore.theme.LocalOreFeedback
import dev.compixel.ui.ore.theme.OreTheme

@Composable
fun OreListItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val colors = OreTheme.colors
    val feedback = LocalOreFeedback.current
    Row(
        modifier
            .heightIn(min = 28.dp)
            .oreFrame(
                if (enabled && pressed) colors.panel
                else if (enabled && hovered) colors.hovered
                else if (selected) lerp(colors.raised, colors.primary, .18f) else colors.raised,
                colors.edge,
                colors.highlight,
            )
            .oreOutline(if (focused && enabled) colors.focus else null)
            .drawBehind {
                if (selected)
                    drawRect(
                        colors.primaryHover,
                        Offset(1.dp.roundToPx().toFloat(), 1.dp.roundToPx().toFloat()),
                        Size(1.5.dp.roundToPx().toFloat(), (size.height - 2.dp.roundToPx()).coerceAtLeast(0f)),
                    )
            }
            .semantics { this.selected = selected }
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button) {
                onClick()
                feedback.activate()
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}
