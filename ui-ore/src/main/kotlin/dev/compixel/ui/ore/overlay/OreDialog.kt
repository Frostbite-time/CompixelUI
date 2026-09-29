package dev.compixel.ui.ore.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.button.OreIconButton
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.layout.OreSurface
import dev.compixel.ui.ore.theme.OreTheme

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun OreDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    closeLabel: String = "Close",
    buttons: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, animateTransition = false),
    ) {
        val colors = OreTheme.colors
        OreSurface(
            modifier.widthIn(max = 230.dp).fillMaxWidth(),
            bottomLedge = 2.dp,
            ledgeColor = colors.ledge,
            frameEdge = colors.frameEdge,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(colors.raised)
                        .heightIn(min = 20.dp)
                        .padding(horizontal = 5.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Spacer(Modifier.width(18.dp))
                    OreText(
                        title,
                        Modifier.weight(1f),
                        style = OreTheme.typography.title,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OreIconButton(OreGlyph.Cross, closeLabel, onDismissRequest, style = OreButtonStyle.Quiet)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(colors.edge))
                Column(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = content,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.highlight))
                Column(
                    Modifier.fillMaxWidth().background(colors.raised).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = buttons,
                )
            }
        }
    }
}
