package dev.composemc.ui.ore.selection

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
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.internal.oreFrame
import dev.composemc.ui.ore.internal.oreOutline
import dev.composemc.ui.ore.theme.LocalOreFeedback
import dev.composemc.ui.ore.theme.OreTheme

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
    val feedback = LocalOreFeedback.current
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
            if (!enabled) colors.raised
            else if (checked) {
                if (pressed) colors.primaryPressed else colors.primary
            } else if (pressed) colors.secondaryPressed else if (hovered) colors.secondaryHover else colors.secondary
        Box(
            Modifier.size(13.dp)
                .oreFrame(
                    fill,
                    if (enabled) colors.edge else colors.disabledEdge,
                    lerp(fill, colors.bevelLight, .28f),
                )
                .oreOutline(if (focused && enabled) colors.focus else null),
            contentAlignment = Alignment.Center,
        ) {
            if (state == ToggleableState.On)
                OreIcon(OreGlyph.Checkmark, color = if (enabled) colors.onPrimary else colors.mutedText)
            else if (state == ToggleableState.Indeterminate)
                Box(Modifier.size(7.dp, 1.dp).background(if (enabled) colors.onPrimary else colors.mutedText))
        }
        if (label != null) OreText(label, color = if (enabled) colors.text else colors.mutedText)
    }
}
