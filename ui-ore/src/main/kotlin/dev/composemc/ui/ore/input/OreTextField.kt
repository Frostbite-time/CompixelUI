package dev.composemc.ui.ore.input

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.internal.oreFrame
import dev.composemc.ui.ore.internal.oreOutline
import dev.composemc.ui.ore.theme.OreTheme

@Composable
fun OreTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    leadingIcon: OreGlyph? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = OreTheme.typography.body,
) {
    val colors = OreTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val outline = if (isError) colors.danger else if (focused && enabled) colors.focus else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) OreText(label)
        BasicTextField(value, onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 21.dp)
                .oreFrame(if (enabled) colors.panel else colors.raised, colors.edge, colors.highlight)
                .oreOutline(outline),
            enabled = enabled, readOnly = readOnly, singleLine = singleLine,
            textStyle = textStyle.copy(color = if (enabled) colors.text else colors.mutedText),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            interactionSource = interactions, cursorBrush = SolidColor(colors.text),
            decorationBox = { inner ->
                Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (leadingIcon != null) OreIcon(leadingIcon, color = colors.mutedText)
                    // The empty editor and fallback-font placeholder can have different heights.
                    Box(Modifier.weight(1f), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                        if (value.isEmpty()) OreText(placeholder, color = colors.mutedText, style = textStyle)
                        inner()
                    }
                }
            })
    }
}
