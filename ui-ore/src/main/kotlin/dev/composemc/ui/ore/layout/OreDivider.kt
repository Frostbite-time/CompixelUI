package dev.composemc.ui.ore.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.theme.OreTheme

@Composable
fun OreDivider(modifier: Modifier = Modifier) {
    val colors = OreTheme.colors
    Box(modifier.fillMaxWidth().height(1.dp).drawBehind {
        drawRect(colors.edge)
        drawRect(colors.highlight.copy(alpha = 0.4f), Offset(0f, size.height / 2), Size(size.width, size.height / 2))
    })
}
