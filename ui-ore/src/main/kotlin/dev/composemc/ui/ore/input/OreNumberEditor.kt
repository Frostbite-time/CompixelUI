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

@Composable
internal fun <T : Any> OreNumberEditor(value: T, onValueChange: (T) -> Unit, modifier: Modifier,
    label: String?, enabled: Boolean, rangeKey: Any, parse: (String) -> T?, clampDraft: (String) -> T?, accepts: (String) -> Boolean,
    adjust: (T, Int, Int) -> T, canDecrease: (T) -> Boolean, canIncrease: (T) -> Boolean,
    keyboardType: KeyboardType, decreaseLabel: String, increaseLabel: String) {
    var draft by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, rangeKey, enabled) {
        if (!focused || !dirty || !enabled) { draft = value.toString(); dirty = false }
    }
    val parsed = parse(draft)
    fun reset() { draft = value.toString(); dirty = false }
    fun commit(resetInvalid: Boolean = false) {
        if (enabled && dirty) {
            val next = parsed ?: clampDraft(draft)
            if (next != null) {
                draft = next.toString(); dirty = false
                if (next != value) onValueChange(next)
            } else if (resetInvalid) reset()
        }
    }
    fun step(direction: Int, scale: Int) {
        if (!enabled) return
        val next = adjust(parsed ?: value, direction, scale)
        draft = next.toString(); dirty = false
        if (next != value) onValueChange(next)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) OreText(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            OreButton("−", { step(-1, 0) }, Modifier.width(21.dp).semantics { contentDescription = decreaseLabel },
                enabled && canDecrease(parsed ?: value), OreButtonStyle.Secondary)
            OreTextField(draft, { if (accepts(it)) { draft = it; dirty = true } },
                Modifier.weight(1f).onFocusChanged {
                    if (focused && !it.isFocused) commit(resetInvalid = true)
                    focused = it.isFocused
                }.onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { commit(); true }
                        Key.Escape -> { reset(); focus.clearFocus(); true }
                        Key.DirectionUp, Key.DirectionDown -> {
                            step(if (event.key == Key.DirectionUp) 1 else -1, if (event.isCtrlPressed) 2 else if (event.isShiftPressed) 1 else 0); true
                        }
                        else -> false
                    }
                }.onPointerEvent(PointerEventType.Scroll) { event ->
                    val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                    if (enabled && delta != 0.0 && event.changes.none { it.isConsumed }) {
                        step(if (delta < 0) 1 else -1, if (event.keyboardModifiers.isCtrlPressed) 2 else if (event.keyboardModifiers.isShiftPressed) 1 else 0)
                        event.changes.forEach { it.consume() }
                    }
                }, enabled = enabled, isError = focused && dirty && parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
            OreButton("+", { step(1, 0) }, Modifier.width(21.dp).semantics { contentDescription = increaseLabel },
                enabled && canIncrease(parsed ?: value), OreButtonStyle.Secondary)
        }
    }
}
