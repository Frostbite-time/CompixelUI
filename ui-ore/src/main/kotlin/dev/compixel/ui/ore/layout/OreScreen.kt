package dev.compixel.ui.ore.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme

/**
 * Centered, bounded game panel; content can use weight(1f) for a lazy list. [showTitleBar] controls the title, close
 * button and separator as one unit.
 */
@Composable
fun OreScreen(
    title: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 340.dp,
    maxHeight: Dp = 380.dp,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "Close",
    footer: (@Composable RowScope.() -> Unit)? = null,
    panelModifier: Modifier = Modifier,
    showTitleBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().background(OreTheme.colors[OreColors.backdrop]).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        OrePanel(
            title,
            Modifier.width(minOf(this.maxWidth, maxWidth)).height(minOf(this.maxHeight, maxHeight)).then(panelModifier),
            onClose,
            closeLabel,
            footer,
            showTitleBar = showTitleBar,
            content = content,
        )
    }
}
