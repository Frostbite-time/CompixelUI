package dev.composemc.ui.ore.button

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.internal.oreButtonContentOffset
import dev.composemc.ui.ore.internal.oreButtonFrame
import dev.composemc.ui.ore.theme.LocalOreContentColor
import dev.composemc.ui.ore.theme.LocalOreFeedback
import dev.composemc.ui.ore.theme.OreTheme

enum class OreButtonStyle { Primary, Secondary, Destructive, Quiet }

@Composable
fun OreButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, style: OreButtonStyle = OreButtonStyle.Primary,
) = OreButton(onClick, modifier, enabled, style) { OreText(text) }

@Composable
fun OreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: OreButtonStyle = OreButtonStyle.Primary,
    role: Role = Role.Button,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val colors = OreTheme.colors
    val feedback = LocalOreFeedback.current
    val ink = when {
        !enabled -> colors.disabledText
        style == OreButtonStyle.Secondary -> colors.ink
        else -> colors.text
    }
    CompositionLocalProvider(LocalOreContentColor provides ink) {
        Row(modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).oreButtonFrame(colors, style, enabled, hovered, pressed && enabled)
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = role) {
            onClick()
            feedback.activate()
        }.padding(contentPadding).oreButtonContentOffset(pressed && enabled),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
