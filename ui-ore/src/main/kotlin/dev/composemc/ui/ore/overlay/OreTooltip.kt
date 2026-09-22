@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.composemc.ui.ore.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.theme.OreTheme

/** Text convenience over the same interactive, nestable tooltip host. */
@Composable
fun OreTooltip(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    lockDelayMillis: Long = 600, exitDelayMillis: Long = 350, content: @Composable () -> Unit) =
    OreTooltip(tooltip = { OreText(text, style = OreTheme.typography.caption) }, modifier = modifier,
        enabled = enabled && text.isNotBlank(), lockDelayMillis = lockDelayMillis,
        exitDelayMillis = exitDelayMillis, maxWidth = 180.dp, content = content)

private class TooltipBranch {
    var open by mutableStateOf(false)
    var anchor by mutableStateOf(false)
    var body by mutableStateOf(false)
    var focused by mutableStateOf(false)
    var pointerDriven by mutableStateOf(false)
    var locked by mutableStateOf(false)
    var children by mutableIntStateOf(0)
    fun close() { open = false; locked = false; body = false }
}
private val LocalTooltipBranch = staticCompositionLocalOf<TooltipBranch?> { null }

/**
 * Immediate hover; the progress line locks the popup after a continuous dwell.
 * An unlocked popup closes immediately on exit. A locked popup permits crossing the gap,
 * and remains alive while its body or any descendant popup is in use. All content stays
 * in the host Compose scene; arbitrary composables (including nested tooltips) are allowed.
 */
@Composable
fun OreTooltip(tooltip: @Composable ColumnScope.() -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, lockDelayMillis: Long = 600, exitDelayMillis: Long = 350,
    maxWidth: Dp = 220.dp, content: @Composable () -> Unit) {
    require(lockDelayMillis in 0..60_000 && exitDelayMillis in 0..60_000 && maxWidth > 0.dp)
    val branch = remember { TooltipBranch() }
    val parent = LocalTooltipBranch.current
    val window = LocalWindowInfo.current
    val density = LocalDensity.current
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val position = remember(density) { OrePopupPosition(gap = with(density) { 3.dp.roundToPx() }) }
    val canShow = enabled && window.isWindowFocused
    val keyboardFocused = branch.focused && !branch.pointerDriven
    LaunchedEffect(canShow) { if (!canShow) branch.close() }
    LaunchedEffect(branch.open, branch.anchor, keyboardFocused) {
        if (!branch.open) { progress.snapTo(0f); return@LaunchedEffect }
        if (!branch.locked && (branch.anchor || keyboardFocused)) {
            progress.snapTo(0f)
            progress.animateTo(1f, androidx.compose.animation.core.tween(lockDelayMillis.toInt(), easing = androidx.compose.animation.core.LinearEasing))
            branch.locked = true
        }
    }
    LaunchedEffect(branch.open, branch.anchor, branch.body, keyboardFocused, branch.children, branch.locked) {
        if (branch.open && !branch.anchor && !keyboardFocused && !branch.body && branch.children == 0) {
            if (branch.locked && exitDelayMillis > 0) {
                val start = withFrameNanos { it }
                do { val elapsed = withFrameNanos { it } - start } while (elapsed < exitDelayMillis * 1_000_000L)
            }
            branch.close()
        }
    }
    Box(modifier.onFocusChanged {
        branch.focused = it.hasFocus
        if (it.hasFocus && canShow && !branch.pointerDriven) branch.open = true
    }.onPointerEvent(PointerEventType.Enter) {
        branch.pointerDriven = true
        branch.anchor = true
        if (canShow) branch.open = true
    }.onPointerEvent(PointerEventType.Exit) {
        branch.anchor = false
        if (!branch.locked) branch.close()
    }.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == androidx.compose.ui.input.key.Key.Escape && branch.open) {
            branch.close(); true
        } else {
            if (it.type == KeyEventType.KeyDown && branch.focused && canShow) { branch.pointerDriven = false; branch.open = true }
            false
        }
    }) {
        content()
        if (branch.open && canShow) {
            DisposableEffect(parent, branch) {
                if (parent != null) parent.children++
                onDispose { if (parent != null) parent.children-- }
            }
            Popup(position, onDismissRequest = { branch.close() }, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
                CompositionLocalProvider(LocalTooltipBranch provides branch) {
                    val available = with(density) { DpSize(window.containerSize.width.toDp(), window.containerSize.height.toDp()) }
                    OreSurface(Modifier.widthIn(max = minOf(maxWidth, available.width)).heightIn(max = available.height)
                        .onPointerEvent(PointerEventType.Enter) { branch.body = true }
                        .onPointerEvent(PointerEventType.Exit) { branch.body = false }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == androidx.compose.ui.input.key.Key.Escape) {
                                branch.close(); true
                            } else false
                        }, color = OreTheme.colors.raised) {
                        Column {
                            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp), content = tooltip)
                            val colors = OreTheme.colors
                            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge)) {
                                Box(Modifier.fillMaxWidth(if (branch.locked) 1f else progress.value).height(1.dp).background(colors.primary))
                            }
                        }
                    }
                }
            }
        }
    }
}
