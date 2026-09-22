@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.composemc.ui.ore.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset

/** Consumes the secondary press, so ordinary Compose actions beneath it do not also run. */
@Composable
fun OreContextMenuArea(items: List<OreMenuItem>, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit) {
    var position by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    LaunchedEffect(enabled) { if (!enabled) position = null }
    Box(modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        if (enabled && event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
            val p = event.changes.first().position
            position = with(density) { DpOffset(p.x.toDp(), p.y.toDp()) }
            event.changes.forEach { it.consume() }
        }
    }) {
        content()
        OreMenu(position != null && enabled, { position = null }, items, offset = position)
    }
}
