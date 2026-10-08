package dev.compixel.ui.ore.inventory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme

/**
 * A slot's amount, drawn like vanilla item counts: [OreColors.slotAmount] digits over a hard shadow of
 * [OreColors.slotAmountShadow] one font pixel down and to the right, legible over any item and slot color. Place it at
 * the bottom-right of the slot's content area; [contentSize] is that area's width. In a 16 dp area one font pixel is
 * two thirds of a dp, so digits are about two thirds of vanilla's size, and the text scales with larger areas. The
 * decimal point takes vanilla's two-pixel width, so four characters such as `1.2K` fit; a longer amount extends to the
 * left instead of being cut off.
 */
@Composable
fun OreSlotAmount(text: String, modifier: Modifier = Modifier, contentSize: Dp = 16.dp) {
    val colors = OreTheme.colors
    val pixel = contentSize / 24
    val density = LocalDensity.current
    val shadow = with(density) { pixel.toPx() }
    val fontSize = with(density) { (pixel * 9).toSp() }
    Box(modifier.wrapContentWidth(Alignment.End, unbounded = true).padding(end = pixel, bottom = pixel)) {
        BasicText(
            remember(text) { narrowDecimalPoints(text) },
            style =
                OreTheme.typography.body.copy(
                    color = colors[OreColors.slotAmount],
                    fontSize = fontSize,
                    lineHeight = fontSize * (4f / 3f),
                    shadow = Shadow(colors[OreColors.slotAmountShadow], Offset(shadow, shadow), 0f),
                ),
            maxLines = 1,
        )
    }
}

// Every Compixel glyph is a six-pixel cell; the decimal point is a one-pixel dot in the middle of its cell, where
// vanilla gives it a two-pixel cell. Tightening the point and the character before it by two pixels each puts the dot
// where vanilla draws it and gives the same width.
private val NARROW = SpanStyle(letterSpacing = (-2f / 9f).em)

private fun narrowDecimalPoints(text: String): AnnotatedString = buildAnnotatedString {
    text.forEachIndexed { index, char ->
        if (char == '.' || text.getOrNull(index + 1) == '.') withStyle(NARROW) { append(char) } else append(char)
    }
}
