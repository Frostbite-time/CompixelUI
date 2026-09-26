package dev.composemc.demo.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.display.OreText as Text
import dev.composemc.ui.ore.navigation.OreTab
import dev.composemc.ui.ore.theme.OreTheme

class ItemBrowserModel {
    var count by mutableIntStateOf(1000)
    var selected by mutableIntStateOf(-1)
    var scrollTarget by mutableIntStateOf(0)
    var firstVisible by mutableIntStateOf(0)
    var hitX by mutableFloatStateOf(0f)
    var hitY by mutableFloatStateOf(0f)
    var previewBounds by mutableStateOf<Rect?>(null)
    val visibleCells = mutableMapOf<Int, Rect>()
    var edgeTooltipBounds: Rect? = null
}

/** The native item slot is injected by the version adapter; this demo has no Minecraft imports. */
@Composable
fun ItemBrowserDemo(
    model: ItemBrowserModel,
    labels: List<String>,
    modifier: Modifier = Modifier,
    previewItem: (@Composable (Int, Modifier) -> Unit)? = null,
    item: @Composable (Int, Modifier) -> Unit,
) {
    require(labels.size >= 4)
    val grid = rememberLazyGridState()
    val sample = previewItem ?: item
    LaunchedEffect(model.scrollTarget) { grid.scrollToItem(model.scrollTarget.coerceIn(0, model.count - 1)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OreTab("1k", model.count == 1000, { model.count = 1000 })
            OreTab("10k", model.count == 10000, { model.count = 10000 })
            Text("Native items", color = OreTheme.colors.mutedText)
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sample(0, Modifier.size(24.dp))
            sample(1, Modifier.size(32.dp))
            Box(
                Modifier.size(36.dp)
                    .onGloballyPositioned { model.previewBounds = it.boundsInRoot() }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF344A39))
            ) {
                sample(
                    3,
                    Modifier.requiredSize(48.dp).graphicsLayer {
                        rotationZ = 25f
                        alpha = 0.75f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                )
            }
            Text("Scale · clip · rotate", style = OreTheme.typography.caption)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(64.dp),
            Modifier.weight(1f).fillMaxWidth(),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(model.count, key = { it }) { index ->
                val source = index % labels.size
                DisposableEffect(index) { onDispose { model.visibleCells.remove(index) } }
                Column(
                    Modifier.fillMaxWidth()
                        .height(52.dp)
                        .background(
                            if (model.selected == index) Color(0xFF344A39) else Color(0xFF242B2D),
                            RoundedCornerShape(4.dp),
                        )
                        .clickable { model.selected = index }
                        .onGloballyPositioned { coordinates ->
                            model.visibleCells[index] = coordinates.boundsInRoot()
                            if (index == grid.firstVisibleItemIndex) {
                                val center = coordinates.boundsInRoot().center
                                model.firstVisible = index
                                model.hitX = center.x
                                model.hitY = center.y
                            }
                        }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    item(source, Modifier.size(28.dp))
                    Text(labels[source], maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Selected: " + model.selected + " · " + model.count + " rows",
                style = OreTheme.typography.caption,
                modifier = Modifier.weight(1f),
            )
            Text("Hover for item details", style = OreTheme.typography.caption)
            sample(
                if (labels.size > 8) 8 else 3,
                Modifier.size(18.dp).onGloballyPositioned { model.edgeTooltipBounds = it.boundsInRoot() },
            )
        }
    }
}
