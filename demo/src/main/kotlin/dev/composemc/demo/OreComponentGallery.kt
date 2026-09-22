package dev.composemc.demo

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.button.OreIconButton
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.input.OreSlider
import dev.composemc.ui.ore.input.OreTextField
import dev.composemc.ui.ore.inventory.OreSlot
import dev.composemc.ui.ore.layout.OreScreen
import dev.composemc.ui.ore.navigation.OreListItem
import dev.composemc.ui.ore.navigation.OreTab
import dev.composemc.ui.ore.overlay.OreDialog
import dev.composemc.ui.ore.selection.OreCheckbox
import dev.composemc.ui.ore.selection.OreSwitch
import dev.composemc.ui.ore.theme.OreTheme

/** A compact, interactive component reference that also renders without Minecraft. */
@Composable
fun OreComponentGallery() {
    var text by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(true) }
    var amount by remember { mutableFloatStateOf(.6f) }
    var dialog by remember { mutableStateOf(false) }
    OreTheme {
        OreScreen("Ore UI", maxWidth = 780.dp, footer = {
            OreButton("Open dialog", { dialog = true }, Modifier.weight(1f))
            OreButton("Disabled", {}, Modifier.weight(1f), enabled = false)
        }) {
            OreText("Default game components", style = OreTheme.typography.title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OreButton("Primary", {}, Modifier.weight(1f))
                OreButton("Secondary", {}, Modifier.weight(1f), style = OreButtonStyle.Secondary)
                OreButton("Remove", {}, Modifier.weight(1f), style = OreButtonStyle.Destructive)
            }
            OreTextField(text, { text = it }, label = "World name / 世界名称", placeholder = "Enter a name…", leadingIcon = OreGlyph.Search)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OreCheckbox(checked, { checked = it }, label = "Enabled")
                OreCheckbox(false, {}, enabled = false, label = "Disabled")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OreSwitch(checked, { checked = it })
                    OreSwitch(false, {})
                    OreSwitch(true, {}, enabled = false)
                }
            }
            OreSlider(amount, { amount = it })
            OreListItem(true, {}, Modifier.fillMaxWidth()) {
                OreIcon(OreGlyph.Network)
                Column(Modifier.weight(1f)) { OreText("Selected world"); OreText("Creative · 本地世界", style = OreTheme.typography.caption, color = OreTheme.colors.mutedText) }
                OreIconButton(OreGlyph.Edit, "Edit", { dialog = true })
            }
            OreListItem(false, {}, Modifier.fillMaxWidth()) { OreIcon(OreGlyph.Network); OreText("Another world", Modifier.weight(1f)); OreIcon(OreGlyph.ArrowRight) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OreTab("Worlds", true, {}); OreTab("Servers", false, {}); OreTab("Locked", false, {}, enabled = false) }
            OreText("Inventory slots", style = OreTheme.typography.caption, color = OreTheme.colors.mutedText)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Triple("Slot", false, false), Triple("Marked", true, false),
                        Triple("Hover", false, true), Triple("Marked hover", true, true)).forEach { (label, marked, hovered) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OreSlot(Modifier.size(28.dp), marked, hovered) {}
                            OreText(label, style = OreTheme.typography.caption)
                        }
                    }
                }
                SlotGroup("Normal group", marked = false)
                SlotGroup("Marked group", marked = true)
            }
        }
        if (dialog) OreDialog("Edit world", { dialog = false }, buttons = {
            OreButton("Save", { dialog = false }, Modifier.fillMaxWidth())
            OreButton("Cancel", { dialog = false }, Modifier.fillMaxWidth(), style = OreButtonStyle.Secondary)
        }) { OreTextField(text, { text = it }, label = "World name") }
    }
}

@Composable
private fun SlotGroup(label: String, marked: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OreText(label, style = OreTheme.typography.caption)
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    repeat(4) { OreSlot(Modifier.size(28.dp), marked = marked) {} }
                }
            }
        }
    }
}
