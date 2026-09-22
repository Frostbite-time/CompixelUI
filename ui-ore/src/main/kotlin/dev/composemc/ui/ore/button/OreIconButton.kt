package dev.composemc.ui.ore.button

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.internal.oreOutline
import dev.composemc.ui.ore.overlay.OreTooltip
import dev.composemc.ui.ore.theme.LocalOreContentColor
import dev.composemc.ui.ore.theme.LocalOreFeedback
import dev.composemc.ui.ore.theme.OreTheme

/** Convenience overload for the bundled pixel glyphs. */
@Composable
fun OreIconButton(glyph: OreGlyph, contentDescription: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, style: OreButtonStyle = OreButtonStyle.Secondary) =
    OreIconButton(contentDescription, onClick, modifier, enabled, style) { contentColor ->
        OreIcon(glyph, color = contentColor)
    }

/**
 * An icon-only action with caller-provided, non-interactive visual content.
 * [icon] receives the foreground color for the current style and enabled state.
 * It may draw a Canvas, Image, painter or prepared native image; no glyph registration is needed.
 * [contentDescription] labels the action and supplies its tooltip. Keep child images decorative.
 * Size the visual inside [icon]; [modifier] controls the button bounds.
 */
@Composable
fun OreIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: OreButtonStyle = OreButtonStyle.Secondary,
    icon: @Composable (contentColor: Color) -> Unit,
) {
    OreTooltip(contentDescription, enabled = enabled) {
        if (style == OreButtonStyle.Quiet) {
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val pressed by interactions.collectIsPressedAsState()
            val focused by interactions.collectIsFocusedAsState()
            val colors = OreTheme.colors
            val feedback = LocalOreFeedback.current
            Box(modifier.size(18.dp).semantics { this.contentDescription = contentDescription }
                .background(if (enabled && pressed) colors.panel else if (enabled && hovered) colors.hovered else Color.Transparent)
                .oreOutline(if (focused && enabled) colors.focus else null)
                .clickable(interactions, indication = null, enabled = enabled, role = Role.Button) { onClick(); feedback.activate() },
                contentAlignment = Alignment.Center) {
                val contentColor = if (enabled) colors.text else colors.mutedText
                CompositionLocalProvider(LocalOreContentColor provides contentColor) {
                    icon(contentColor)
                }
            }
        } else {
            OreButton(onClick, modifier.size(18.dp).semantics { this.contentDescription = contentDescription }, enabled, style, contentPadding = PaddingValues(4.dp)) {
                icon(LocalOreContentColor.current)
            }
        }
    }
}
