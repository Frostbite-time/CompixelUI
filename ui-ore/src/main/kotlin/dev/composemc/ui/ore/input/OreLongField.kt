package dev.composemc.ui.ore.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/** Exact signed 64-bit editor; stepping saturates without a floating-point conversion. */
@Composable
fun OreLongField(value: Long, onValueChange: (Long) -> Unit, modifier: Modifier = Modifier,
    range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE, label: String? = null, enabled: Boolean = true,
    step: Long = 1, shiftStep: Long = 10, controlStep: Long = 100,
    decreaseLabel: String = "Decrease", increaseLabel: String = "Increase") {
    require(!range.isEmpty() && value in range && step > 0 && shiftStep > 0 && controlStep > 0)
    OreNumberEditor(value, onValueChange, modifier, label, enabled, range,
        parse = { it.toLongOrNull()?.takeIf { number -> number in range } },
        accepts = { text -> text.length <= 20 && text.withIndex().all { (i, c) -> c in '0'..'9' || i == 0 && c == '-' && range.first < 0 } },
        adjust = { base, direction, scale ->
            val amount = when (scale) { 2 -> controlStep; 1 -> shiftStep; else -> step }
            val next = if (direction > 0) {
                if (base > Long.MAX_VALUE - amount) Long.MAX_VALUE else base + amount
            } else if (base < Long.MIN_VALUE + amount) Long.MIN_VALUE else base - amount
            next.coerceIn(range)
        }, canDecrease = { it > range.first }, canIncrease = { it < range.last },
        keyboardType = KeyboardType.Number, decreaseLabel = decreaseLabel, increaseLabel = increaseLabel)
}
