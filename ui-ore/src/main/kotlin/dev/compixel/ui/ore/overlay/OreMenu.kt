@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.compixel.ui.ore.overlay

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.compixel.ui.LocalUiFeedback
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreIcon
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.layout.OreSurface
import dev.compixel.ui.ore.navigation.OreListItem
import dev.compixel.ui.ore.scroll.OreScrollbar
import dev.compixel.ui.ore.theme.OreTheme
import kotlinx.coroutines.launch

/** Stable id and presentation belong to the caller; activation never implies a server-side operation. */
data class OreMenuItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val shortcut: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Place inside the anchor's Box. A non-null offset opens at an anchor-local pointer position. */
@Composable
fun OreMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<OreMenuItem>,
    modifier: Modifier = Modifier,
    offset: DpOffset? = null,
    initialIndex: Int = 0,
) {
    require(items.map { it.id }.toSet().size == items.size) { "Menu item ids must be unique" }
    if (!expanded) return
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val height = with(density) { window.containerSize.height.toDp() }
    val width = with(density) { window.containerSize.width.toDp() }
    val point = offset?.let { with(density) { IntOffset(it.x.roundToPx(), it.y.roundToPx()) } }
    val gap = with(density) { 3.dp.roundToPx() }
    val position = remember(point, gap) { OrePopupPosition(point, gap) }
    val feedback = LocalUiFeedback.current
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val showScrollbar by remember { derivedStateOf { list.canScrollForward || list.canScrollBackward } }
    val scope = rememberCoroutineScope()
    var activeId by remember {
        mutableStateOf(items.getOrNull(initialIndex)?.takeIf { it.enabled }?.id ?: items.firstOrNull { it.enabled }?.id)
    }
    val active = items.indexOfFirst { it.id == activeId && it.enabled }
    LaunchedEffect(items.map { it.id to it.enabled }) {
        if (active < 0) activeId = items.firstOrNull { it.enabled }?.id
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(window.isWindowFocused) { if (!window.isWindowFocused) onDismissRequest() }
    fun activate(index: Int, keyboard: Boolean = false) {
        val item = items.getOrNull(index)?.takeIf { it.enabled } ?: return
        onDismissRequest()
        item.onClick()
        if (keyboard) feedback.activate()
    }
    Popup(position, onDismissRequest, properties = PopupProperties(focusable = true)) {
        OreSurface(
            modifier
                .widthIn(max = width)
                .width(minOf(136.dp, width))
                .heightIn(max = minOf(200.dp, height))
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else {
                        val enabledIndices = items.indices.filter { items[it].enabled }
                        val next =
                            when (event.key) {
                                Key.DirectionDown ->
                                    enabledIndices.firstOrNull { it > active } ?: enabledIndices.firstOrNull()
                                Key.DirectionUp ->
                                    enabledIndices.lastOrNull { it < active } ?: enabledIndices.lastOrNull()
                                Key.MoveHome -> enabledIndices.firstOrNull()
                                Key.MoveEnd -> enabledIndices.lastOrNull()
                                else -> null
                            }
                        when {
                            next != null -> {
                                activeId = items[next].id
                                scope.launch { list.scrollToItem(next) }
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar -> {
                                activate(active, keyboard = true)
                                true
                            }
                            event.key == Key.Escape || event.key == Key.Tab -> {
                                onDismissRequest()
                                true
                            }
                            else -> false
                        }
                    }
                }
                .focusable()
        ) {
            Box {
                LazyColumn(
                    Modifier.padding(3.dp).padding(end = if (showScrollbar) 7.dp else 0.dp),
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        OreListItem(
                            index == active,
                            { activate(index) },
                            Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Enter) {
                                if (item.enabled) activeId = item.id
                            },
                            enabled = item.enabled,
                            minimumHeight = 22.dp,
                        ) {
                            if (item.checked != null)
                                Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                                    if (item.checked) OreIcon(OreGlyph.Checkmark)
                                }
                            OreText(
                                item.label,
                                Modifier.weight(1f),
                                color =
                                    if (!item.enabled) OreTheme.colors.mutedText
                                    else if (item.destructive) lerp(OreTheme.colors.danger, OreTheme.colors.text, .65f)
                                    else OreTheme.colors.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.shortcut != null)
                                OreText(
                                    item.shortcut,
                                    style = OreTheme.typography.caption,
                                    color = OreTheme.colors.mutedText,
                                )
                        }
                    }
                }
                if (showScrollbar) {
                    Box(Modifier.matchParentSize()) {
                        OreScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}
