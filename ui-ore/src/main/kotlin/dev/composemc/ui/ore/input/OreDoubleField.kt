package dev.composemc.ui.ore.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal

/** Finite double editor. Decimal stepping avoids accumulated binary rounding in ordinary decimal inputs. */
@Composable
fun OreDoubleField(value: Double, onValueChange: (Double) -> Unit, modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Double> = -Double.MAX_VALUE..Double.MAX_VALUE,
    label: String? = null, enabled: Boolean = true, step: Double = 1.0,
    shiftStep: Double = 10.0, controlStep: Double = 100.0,
    decreaseLabel: String = "Decrease", increaseLabel: String = "Increase") {
    require(range.start.isFinite() && range.endInclusive.isFinite() && !range.isEmpty() && value.isFinite() && value in range)
    require(listOf(step, shiftStep, controlStep).all { it.isFinite() && it > 0 })
    OreNumberEditor(value, onValueChange, modifier, label, enabled, range,
        parse = { it.toDoubleOrNull()?.takeIf { number -> number.isFinite() && number in range } },
        clampDraft = { text ->
            text.toBigDecimalOrNull()?.coerceIn(BigDecimal.valueOf(range.start), BigDecimal.valueOf(range.endInclusive))?.toDouble()
                ?: text.toDoubleOrNull()?.takeUnless { it.isNaN() }?.coerceIn(range.start, range.endInclusive)
        },
        accepts = { text -> text.length <= 128 && text.matches(decimalDraft) },
        adjust = { base, direction, scale ->
            val amount = when (scale) { 2 -> controlStep; 1 -> shiftStep; else -> step }
            BigDecimal.valueOf(base).add(BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(direction.toLong())))
                .toDouble().coerceIn(range.start, range.endInclusive)
        }, canDecrease = { it > range.start }, canIncrease = { it < range.endInclusive },
        keyboardType = KeyboardType.Decimal, decreaseLabel = decreaseLabel, increaseLabel = increaseLabel)
}

private val decimalDraft = Regex("[+-]?[0-9]*(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]*)?")
