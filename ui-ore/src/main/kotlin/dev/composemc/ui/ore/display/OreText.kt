package dev.composemc.ui.ore.display

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import dev.composemc.ui.ore.theme.LocalOreContentColor
import dev.composemc.ui.ore.theme.OreTheme

@Composable
fun OreText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalOreContentColor.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = OreTheme.typography.body,
) {
    BasicText(
        text,
        modifier,
        style =
            style.merge(
                TextStyle(
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    textAlign = textAlign ?: TextAlign.Unspecified,
                )
            ),
        maxLines = maxLines,
        overflow = overflow,
    )
}
