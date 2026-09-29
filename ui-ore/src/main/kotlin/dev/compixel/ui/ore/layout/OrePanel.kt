package dev.compixel.ui.ore.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.button.OreIconButton
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.theme.OreTheme

/** When [showTitleBar] is false, the title, close button and separator occupy no space. */
@Composable
fun OrePanel(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "Close",
    footer: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 5.dp, top = 5.dp, end = 5.dp, bottom = 6.dp),
    showTitleBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OreTheme.colors
    OreSurface(modifier, bottomLedge = 2.dp, ledgeColor = colors.ledge, frameEdge = colors.frameEdge) {
        Column(Modifier.fillMaxSize()) {
            if (showTitleBar) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(colors.raised)
                        .heightIn(min = 20.dp)
                        .padding(horizontal = 5.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onClose != null) Spacer(Modifier.width(18.dp))
                    OreText(
                        title,
                        Modifier.weight(1f),
                        style = OreTheme.typography.title,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onClose != null)
                        OreIconButton(OreGlyph.Cross, closeLabel, onClose, style = OreButtonStyle.Quiet)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(colors.ledge))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.highlight))
                Row(
                    Modifier.fillMaxWidth().background(colors.raised).padding(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = footer,
                )
            }
        }
    }
}
