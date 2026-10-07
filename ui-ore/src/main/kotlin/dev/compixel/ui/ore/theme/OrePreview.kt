package dev.compixel.ui.ore.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.input.OreSlider
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.inventory.OreSlot
import dev.compixel.ui.ore.layout.OrePanel
import dev.compixel.ui.ore.navigation.OreListItem
import dev.compixel.ui.ore.selection.OreCheckbox
import dev.compixel.ui.ore.selection.OreRadioButton
import dev.compixel.ui.ore.selection.OreSwitch
import dev.compixel.ui.ore.selection.OreTabButton

/**
 * What the color editor shows for Ore: one of each kind of control, in their usual states; without words to translate.
 */
@Composable
internal fun OrePreview() {
    var tab by remember { mutableIntStateOf(0) }
    var checked by remember { mutableStateOf(true) }
    var value by remember { mutableFloatStateOf(.6f) }
    var text by remember { mutableStateOf("Aa 123") }
    val colors = OreTheme.colors
    OrePanel("Ore", Modifier.width(220.dp).heightIn(max = 226.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OreTabButton(listOf("I", "II", "III"), tab, { tab = it })
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                OreButton("Aa", {})
                OreButton("Aa", {}, style = OreButtonStyle.Secondary)
                OreButton("Aa", {}, style = OreButtonStyle.Destructive)
                OreButton("Aa", {}, enabled = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OreSwitch(checked, { checked = it })
                OreCheckbox(checked, { checked = it })
                OreRadioButton(checked, { checked = !checked })
                OreSlider(value, { value = it }, Modifier.weight(1f))
            }
            OreTextField(text, { text = it })
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                OreSlot {}
                OreSlot(highlighted = true) {}
                OreSlot(marked = true) {}
                OreSlot(marked = true, highlighted = true) {}
            }
            OreListItem(true, {}, Modifier.fillMaxWidth()) { OreText("Aa") }
            OreListItem(false, {}, Modifier.fillMaxWidth()) { OreText("Aa", color = colors[OreColors.mutedText]) }
        }
    }
}
