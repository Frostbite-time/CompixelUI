package dev.composemc.ui.ore.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.internal.oreOutline
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.scroll.OreScrollbar
import dev.composemc.ui.ore.theme.LocalOreContentColor
import dev.composemc.ui.ore.theme.LocalOreFeedback
import dev.composemc.ui.ore.theme.OreTheme
import kotlinx.coroutines.launch

/** Immutable caller-owned tree. Ids must be unique across the entire tree, including collapsed branches. */
data class OreTreeNode(
    val id: String,
    val label: String,
    val children: List<OreTreeNode> = emptyList(),
    val enabled: Boolean = true,
)

private data class TreeRow(val node: OreTreeNode, val depth: Int, val parent: String?)

/** Lazy, single-selection tree. Expanded ids, selection, and domain operations stay in the consumer. */
@Composable
fun OreTreeView(
    nodes: List<OreTreeNode>,
    selectedId: String?,
    onSelectionChange: (String) -> Unit,
    expandedIds: Set<String>,
    onExpandedChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    nodeContent: (@Composable RowScope.(OreTreeNode) -> Unit)? = null,
) {
    remember(nodes) {
        val ids = hashSetOf<String>()
        val pending = java.util.ArrayDeque(nodes)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            require(node.id.isNotEmpty() && ids.add(node.id)) { "Tree ids must be nonempty and unique: ${node.id}" }
            pending.addAll(node.children)
        }
    }
    val rows =
        remember(nodes, expandedIds) {
            val result = mutableListOf<TreeRow>()
            val pending = java.util.ArrayDeque<TreeRow>()
            nodes.asReversed().forEach { pending.addFirst(TreeRow(it, 0, null)) }
            while (pending.isNotEmpty()) {
                val row = pending.removeFirst()
                result += row
                if (row.node.id in expandedIds)
                    row.node.children.asReversed().forEach { pending.addFirst(TreeRow(it, row.depth + 1, row.node.id)) }
            }
            result
        }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var focusedId by remember { mutableStateOf(selectedId) }
    var hasFocus by remember { mutableStateOf(false) }
    val feedback = LocalOreFeedback.current
    LaunchedEffect(rows) {
        if (rows.none { it.node.id == focusedId && it.node.enabled })
            focusedId =
                rows.firstOrNull { it.node.enabled && it.node.id == selectedId }?.node?.id
                    ?: rows.firstOrNull { it.node.enabled }?.node?.id
    }
    fun move(index: Int) {
        if (index !in rows.indices) return
        focusedId = rows[index].node.id
        scope.launch { list.animateScrollToItem(index) }
    }
    OreSurface(
        modifier
            .focusRequester(focus)
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) false
                else {
                    val index = rows.indexOfFirst { it.node.id == focusedId }
                    val row = rows.getOrNull(index)
                    when (event.key) {
                        Key.DirectionDown -> {
                            rows.indices.firstOrNull { it > index && rows[it].node.enabled }?.let(::move)
                            true
                        }
                        Key.DirectionUp -> {
                            rows.indices.lastOrNull { it < index && rows[it].node.enabled }?.let(::move)
                            true
                        }
                        Key.MoveHome -> {
                            rows.indexOfFirst { it.node.enabled }.let(::move)
                            true
                        }
                        Key.MoveEnd -> {
                            rows.indexOfLast { it.node.enabled }.let(::move)
                            true
                        }
                        Key.DirectionRight -> {
                            if (row != null && row.node.children.isNotEmpty()) {
                                if (row.node.id !in expandedIds) onExpandedChange(row.node.id, true)
                                else
                                    rows.indices
                                        .firstOrNull {
                                            it > index && rows[it].parent == row.node.id && rows[it].node.enabled
                                        }
                                        ?.let(::move)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (row != null) {
                                if (row.node.id in expandedIds && row.node.children.isNotEmpty())
                                    onExpandedChange(row.node.id, false)
                                else rows.indexOfFirst { it.node.id == row.parent && it.node.enabled }.let(::move)
                            }
                            true
                        }
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.Spacebar -> {
                            row?.takeIf { it.node.enabled }
                                ?.let {
                                    onSelectionChange(it.node.id)
                                    feedback.activate()
                                }
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusable(enabled)
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(3.dp).padding(end = 8.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(rows, key = { it.node.id }) { row ->
                val node = row.node
                OreListItem(
                    selectedId == node.id,
                    {
                        focus.requestFocus()
                        focusedId = node.id
                        onSelectionChange(node.id)
                    },
                    Modifier.fillMaxWidth()
                        .padding(start = (row.depth.coerceAtMost(32) * 10).dp)
                        .oreOutline(if (focusedId == node.id && enabled && hasFocus) OreTheme.colors.focus else null)
                        .semantics { contentDescription = node.label },
                    enabled && node.enabled,
                ) {
                    if (node.children.isNotEmpty())
                        OreButton(
                            if (node.id in expandedIds) "−" else "+",
                            {
                                focus.requestFocus()
                                focusedId = node.id
                                onExpandedChange(node.id, node.id !in expandedIds)
                            },
                            Modifier.size(16.dp),
                            enabled && node.enabled,
                            OreButtonStyle.Quiet,
                        )
                    else Spacer(Modifier.width(16.dp))
                    CompositionLocalProvider(
                        LocalOreContentColor provides
                            if (enabled && node.enabled) OreTheme.colors.text else OreTheme.colors.mutedText
                    ) {
                        if (nodeContent != null) nodeContent(node)
                        else OreText(node.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        OreScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), enabled)
    }
}
