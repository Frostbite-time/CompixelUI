package dev.compixel.ui.ore.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreIcon
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.internal.oreFrame
import dev.compixel.ui.ore.internal.oreOutline
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme

@Composable
fun OreCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) =
    OreCheckbox(
        if (checked) ToggleableState.On else ToggleableState.Off,
        { onCheckedChange(!checked) },
        modifier,
        enabled,
        label,
    )

/** Indeterminate represents a mixed group. The caller chooses the next state on activation. */
@Composable
fun OreCheckbox(
    state: ToggleableState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val checked = state != ToggleableState.Off
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val colors = OreTheme.colors
    val feedback = LocalUiFeedback.current
    Row(
        modifier.heightIn(min = 18.dp).triStateToggleable(
            state,
            interactions,
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
        ) {
            onClick()
            feedback.activate()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val fill =
            if (!enabled) colors[OreColors.raised]
            else if (checked) {
                if (pressed) colors[OreColors.primaryPressed] else colors[OreColors.primary]
            } else if (pressed) colors[OreColors.secondaryPressed]
            else if (hovered) colors[OreColors.secondaryHover] else colors[OreColors.secondary]
        Box(
            Modifier.size(13.dp)
                .oreFrame(
                    fill,
                    if (enabled) colors[OreColors.edge] else colors[OreColors.disabledEdge],
                    lerp(fill, colors[OreColors.bevelLight], .28f),
                )
                .oreOutline(if (focused && enabled) colors[OreColors.focus] else null),
            contentAlignment = Alignment.Center,
        ) {
            if (state == ToggleableState.On)
                OreIcon(
                    OreGlyph.Checkmark,
                    color = if (enabled) colors[OreColors.onPrimary] else colors[OreColors.mutedText],
                )
            else if (state == ToggleableState.Indeterminate)
                Box(
                    Modifier.size(7.dp, 1.dp)
                        .background(if (enabled) colors[OreColors.onPrimary] else colors[OreColors.mutedText])
                )
        }
        if (label != null) OreText(label, color = if (enabled) colors[OreColors.text] else colors[OreColors.mutedText])
    }
}
