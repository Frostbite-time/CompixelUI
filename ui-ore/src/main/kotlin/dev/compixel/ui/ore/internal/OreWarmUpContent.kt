package dev.compixel.ui.ore.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreIcon
import dev.compixel.ui.ore.display.OreProgressBar
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.input.OreIntField
import dev.compixel.ui.ore.input.OreSlider
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.inventory.OreSlot
import dev.compixel.ui.ore.layout.OreDivider
import dev.compixel.ui.ore.layout.OrePanel
import dev.compixel.ui.ore.navigation.OreListItem
import dev.compixel.ui.ore.navigation.OreTab
import dev.compixel.ui.ore.overlay.OreDialog
import dev.compixel.ui.ore.overlay.OreMenu
import dev.compixel.ui.ore.overlay.OreMenuItem
import dev.compixel.ui.ore.overlay.OreTooltip
import dev.compixel.ui.ore.scroll.OreScrollbar
import dev.compixel.ui.ore.selection.OreCheckbox
import dev.compixel.ui.ore.selection.OreSwitch
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme

/**
 * What a host's background warm-up composes inside Ore's design before the first screen, so that screen does not wait
 * for Ore and Compose to load: Ore's common controls, a lazy list, a menu and a dialog, with the layouts and drawing
 * screens usually add. It is never shown.
 */
@Composable
fun OreWarmUpContent() {
    var text by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0.5f) }
    var count by remember { mutableIntStateOf(1) }
    var tab by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        OrePanel("CompixelUI", Modifier.size(maxWidth, maxHeight), onClose = {}) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { OreTab("Tab $it", tab == it, { tab = it }) }
            }
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Every text style, with the scripts the Compixel font covers beyond Latin
                    OreText("Ore 0123456789 中文 日本語 한국어", style = OreTheme.typography.title)
                    OreText("Body", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OreText("Caption", style = OreTheme.typography.caption)
                    OreTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "Search")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OreTooltip("Tooltip") { OreButton("Button", {}) }
                        OreButton("Quiet", {}, style = OreButtonStyle.Quiet)
                        OreSwitch(checked, { checked = it })
                        OreCheckbox(checked, { checked = it }, label = "Check")
                    }
                    OreSlider(level, { level = it }, Modifier.fillMaxWidth())
                    OreIntField(count, { count = it }, Modifier.width(80.dp), range = 0..64)
                    Row { repeat(9) { OreSlot(marked = it == 0) { OreIcon(OreGlyph.Plus, Modifier.fillMaxSize()) } } }
                    OreProgressBar(0.5f, Modifier.fillMaxWidth())
                    OreDivider()
                    // A custom layout, a position callback, a shaped border and a gradient
                    val colors = OreTheme.colors
                    Box(
                        Modifier.fillMaxWidth()
                            .height(6.dp)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }
                            .onGloballyPositioned {}
                            .border(1.dp, colors[OreColors.edge], CutCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(listOf(colors[OreColors.raised], colors[OreColors.highlight]))
                            )
                    )
                }
                OreScrollbar(scroll, Modifier.fillMaxHeight())
                LazyColumn(Modifier.weight(1f), state = list) {
                    items(40) { OreListItem(it == 0, {}) { OreText("Row $it") } }
                }
                OreScrollbar(list, Modifier.fillMaxHeight())
            }
        }
        // Menus and dialogs draw in layers of their own.
        OreMenu(true, {}, listOf(OreMenuItem("a", "Item") {}, OreMenuItem("b", "Other", checked = true) {}))
        OreDialog("Dialog", {}, buttons = { OreButton("OK", {}) }) { OreText("Text") }
    }
}
