package dev.compixel.ui.ore.selection

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.internal.oreButtonContentOffset
import dev.compixel.ui.ore.internal.oreButtonFrame
import dev.compixel.ui.ore.theme.LocalOreFeedback
import dev.compixel.ui.ore.theme.OreTheme

/** Joined Ore choice buttons. The caller owns exactly one selected option; this is not page navigation. */
@Composable
fun OreTabButton(
    options: List<String>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionEnabled: (Int) -> Boolean = { true },
) {
    require(options.isNotEmpty() && selectedIndex in options.indices)
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    val feedback = LocalOreFeedback.current
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEachIndexed { index, label ->
            val active = enabled && optionEnabled(index)
            val selected = index == selectedIndex
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val pressed by interactions.collectIsPressedAsState()
            val focused by interactions.collectIsFocusedAsState()
            val colors = OreTheme.colors
            val depressed = selected || pressed && active
            fun choose(next: Int) {
                requesters[next].requestFocus()
                if (next != selectedIndex) {
                    onSelectionChange(next)
                    feedback.activate()
                }
            }
            Box(
                Modifier.weight(1f)
                    .heightIn(min = 25.dp)
                    .focusRequester(requesters[index])
                    .onPreviewKeyEvent { event ->
                        if (!enabled || event.type != KeyEventType.KeyDown) false
                        else {
                            val candidates = options.indices.filter(optionEnabled)
                            val next =
                                when (event.key) {
                                    Key.DirectionLeft -> candidates.lastOrNull { it < index } ?: candidates.lastOrNull()
                                    Key.DirectionRight ->
                                        candidates.firstOrNull { it > index } ?: candidates.firstOrNull()
                                    Key.MoveHome -> candidates.firstOrNull()
                                    Key.MoveEnd -> candidates.lastOrNull()
                                    else -> null
                                }
                            if (next != null) {
                                choose(next)
                                true
                            } else false
                        }
                    }
                    .oreButtonFrame(
                        colors,
                        if (selected) OreButtonStyle.Primary else OreButtonStyle.Secondary,
                        active,
                        hovered,
                        pressed && active,
                        depressed = depressed,
                        focused = focused,
                        selectedMark = selected,
                    )
                    .selectable(selected, interactions, indication = null, enabled = active, role = Role.RadioButton) {
                        choose(index)
                    }
                    .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 4.dp)
                    .oreButtonContentOffset(depressed),
                contentAlignment = Alignment.Center,
            ) {
                OreText(
                    label,
                    color =
                        if (!active) colors.disabledText else if (selected) colors.onPrimary else colors.onSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
