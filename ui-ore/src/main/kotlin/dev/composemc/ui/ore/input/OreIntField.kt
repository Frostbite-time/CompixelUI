package dev.composemc.ui.ore.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/** Bounded integer editor. Typing commits on Enter/focus loss; buttons, arrows and wheel commit immediately. */
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
    OreNumberEditor(value, onValueChange, modifier, label, enabled, range,
        parse = { it.toIntOrNull()?.takeIf { number -> number in range } },
        clampDraft = { it.toLongOrNull()?.coerceIn(range.first.toLong(), range.last.toLong())?.toInt() },
        accepts = { text -> text.length <= 11 && text.withIndex().all { (i, c) -> c in '0'..'9' || i == 0 && c == '-' && range.first < 0 } },
        adjust = { base, direction, scale ->
            val amount = when (scale) { 2 -> controlStep; 1 -> shiftStep; else -> step }
            (base.toLong() + direction.toLong() * amount).coerceIn(range.first.toLong(), range.last.toLong()).toInt()
        }, canDecrease = { it > range.first }, canIncrease = { it < range.last },
        keyboardType = KeyboardType.Number, decreaseLabel = decreaseLabel, increaseLabel = increaseLabel)
}
