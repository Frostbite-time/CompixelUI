@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.composemc.ui.ore.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.display.OreText

/** Bounded integer editing. Typing commits on Enter/focus loss; buttons, arrows and wheel commit immediately.
 * Invalid/partial input never invokes the callback. Escape or invalid focus loss restores the supplied value.
 * Callers own the value and server validation; no game/network types are used here.
 */
@Composable
fun OreIntField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
    label: String? = null,
    enabled: Boolean = true,
    step: Int = 1,
    shiftStep: Int = 10,
    controlStep: Int = 100,
    decreaseLabel: String = "Decrease",
    increaseLabel: String = "Increase",
) {
    require(!range.isEmpty() && value in range && step > 0 && shiftStep > 0 && controlStep > 0)
    var draft by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, range, enabled) {
        if (!focused || !dirty || !enabled) { draft = value.toString(); dirty = false }
    }
    val parsed = draft.toIntOrNull()
    val invalid = parsed == null || parsed !in range
    fun commit() {
        if (enabled && dirty && parsed != null && parsed in range) {
            dirty = false; draft = parsed.toString()
            if (parsed != value) onValueChange(parsed)
        }
    }
    fun adjust(direction: Int, amount: Int) {
        if (!enabled) return
        val base = draft.toLongOrNull()?.coerceIn(range.first.toLong(), range.last.toLong()) ?: value.toLong()
        val next = (base + direction.toLong() * amount).coerceIn(range.first.toLong(), range.last.toLong()).toInt()
        draft = next.toString(); dirty = false
        if (next != value) onValueChange(next)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) OreText(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            OreButton("−", { adjust(-1, step) }, Modifier.width(21.dp).semantics { contentDescription = decreaseLabel },
                enabled = enabled && (parsed ?: value) > range.first, style = OreButtonStyle.Secondary)
            OreTextField(draft, { text ->
                if (text.length <= 11 && text.withIndex().all { (i,c) -> c in '0'..'9' || i == 0 && c == '-' && range.first < 0 }) {
                    draft = text; dirty = true
                }
            }, Modifier.weight(1f).onFocusChanged {
                if (focused && !it.isFocused) {
                    if (invalid) { draft = value.toString(); dirty = false } else commit()
                }
                focused = it.isFocused
            }.onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { commit(); true }
                    Key.Escape -> { draft = value.toString(); dirty = false; focus.clearFocus(); true }
                    Key.DirectionUp, Key.DirectionDown -> {
                        adjust(if (event.key == Key.DirectionUp) 1 else -1, if (event.isCtrlPressed) controlStep else if (event.isShiftPressed) shiftStep else step); true
                    }
                    else -> false
                }
            }.onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                if (enabled && delta != 0.0 && event.changes.none { it.isConsumed }) {
                    adjust(if (delta < 0) 1 else -1,
                        if (event.keyboardModifiers.isCtrlPressed) controlStep else if (event.keyboardModifiers.isShiftPressed) shiftStep else step)
                    event.changes.forEach { it.consume() }
                }
            }, enabled = enabled, isError = focused && dirty && invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OreButton("+", { adjust(1, step) }, Modifier.width(21.dp).semantics { contentDescription = increaseLabel },
                enabled = enabled && (parsed ?: value) < range.last, style = OreButtonStyle.Secondary)
        }
    }
}
