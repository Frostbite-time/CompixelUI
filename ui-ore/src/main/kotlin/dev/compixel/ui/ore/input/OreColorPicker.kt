package dev.compixel.ui.ore.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.internal.oreOutline
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme
import kotlin.math.max
import kotlin.math.min

/**
 * RGBA picker with an HSV plane, hue/alpha tracks and a #RRGGBB / #RRGGBBAA editor. [planeHeight] lets a short screen
 * give the plane less room.
 */
@Composable
fun OreColorPicker(
    value: Color,
    onValueChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showAlpha: Boolean = true,
    planeHeight: Dp = 100.dp,
    planeLabel: String = "Saturation and brightness",
    hueLabel: String = "Hue",
    alphaLabel: String = "Opacity",
) {
    require(value != Color.Unspecified)
    val color = value.convert(androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb)
    val hsv = colorHsv(color)
    var retainedHue by remember { mutableFloatStateOf(hsv[0]) }
    val choice = remember { Choice() }
    val chosen = choice.hsv.takeIf { choice.represents(color) }
    SideEffect { choice.settle(color) }
    val hue = chosen?.get(0) ?: if (hsv[1] > 0f && hsv[2] > 0f) hsv[0] else retainedHue
    val saturation = chosen?.get(1) ?: hsv[1]
    val brightness = chosen?.get(2) ?: hsv[2]
    fun emit(h: Float = hue, s: Float = saturation, v: Float = brightness, a: Float = color.alpha) {
        val picked = floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        retainedHue = picked[0]
        val next = Color.hsv(picked[0], picked[1], picked[2], a.coerceIn(0f, 1f))
        choice.choose(picked, color, next)
        if (next != color) onValueChange(next)
    }
    var hex by remember { mutableStateOf(colorHex(color, showAlpha)) }
    var dirty by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(color, showAlpha, enabled) {
        if (!focused || !dirty || !enabled) {
            hex = colorHex(color, showAlpha)
            dirty = false
        }
    }
    fun commit() {
        parseColorHex(hex, showAlpha, color.alpha)?.let {
            if (it != color) onValueChange(it)
            hex = colorHex(it, showAlpha)
            dirty = false
        }
    }
    val theme = OreTheme.colors
    val planeFocus = remember { FocusRequester() }
    var planeFocused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth()
                .height(planeHeight)
                .focusRequester(planeFocus)
                .onFocusChanged { planeFocused = it.isFocused }
                .semantics {
                    contentDescription = planeLabel
                    stateDescription = "${(saturation*100).toInt()}%, ${(brightness*100).toInt()}%"
                }
                .onPreviewKeyEvent {
                    if (!enabled || it.type != KeyEventType.KeyDown) false
                    else {
                        val step = if (it.isShiftPressed) .1f else .01f
                        when (it.key) {
                            Key.DirectionLeft -> {
                                emit(s = saturation - step)
                                true
                            }
                            Key.DirectionRight -> {
                                emit(s = saturation + step)
                                true
                            }
                            Key.DirectionUp -> {
                                emit(v = brightness + step)
                                true
                            }
                            Key.DirectionDown -> {
                                emit(v = brightness - step)
                                true
                            }
                            else -> false
                        }
                    }
                }
                .orePointDrag(enabled) { p, size ->
                    planeFocus.requestFocus()
                    emit(s = p.x / size.width.coerceAtLeast(1), v = 1f - p.y / size.height.coerceAtLeast(1))
                }
                .focusable(enabled)
                .oreOutline(if (planeFocused) theme[OreColors.focus] else null)
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val center = Offset(saturation * size.width, (1 - brightness) * size.height)
            drawRect(
                Color.Black,
                center - Offset(4.dp.toPx(), 4.dp.toPx()),
                Size(8.dp.toPx(), 8.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
            drawRect(
                Color.White,
                center - Offset(3.dp.toPx(), 3.dp.toPx()),
                Size(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
            if (!enabled) drawRect(theme[OreColors.panel].copy(alpha = .55f))
        }
        ColorTrack(
            hue / 360f,
            { emit(h = it * 360f) },
            hueLabel,
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            enabled,
        )
        if (showAlpha)
            ColorTrack(
                color.alpha,
                { emit(a = it) },
                alphaLabel,
                listOf(color.copy(alpha = 0f), color.copy(alpha = 1f)),
                enabled,
                checker = true,
            )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Canvas(Modifier.size(24.dp)) {
                drawChecker()
                drawRect(color)
                drawRect(theme[OreColors.edge], style = Stroke(1.dp.toPx()))
            }
            OreTextField(
                hex,
                { input ->
                    if (
                        input.length <= (if (showAlpha) 9 else 7) &&
                            input.removePrefix("#").all { it.isDigit() && it <= '9' || it.lowercaseChar() in 'a'..'f' }
                    ) {
                        hex = input
                        dirty = true
                    }
                },
                Modifier.weight(1f)
                    .semantics { contentDescription = if (showAlpha) "RGBA hex" else "RGB hex" }
                    .onFocusChanged {
                        if (focused && !it.isFocused) {
                            if (parseColorHex(hex, showAlpha, color.alpha) == null) {
                                hex = colorHex(color, showAlpha)
                                dirty = false
                            } else commit()
                        }
                        focused = it.isFocused
                    }
                    .onPreviewKeyEvent {
                        if (!enabled || it.type != KeyEventType.KeyDown) false
                        else
                            when (it.key) {
                                Key.Enter,
                                Key.NumPadEnter -> {
                                    commit()
                                    true
                                }
                                Key.Escape -> {
                                    hex = colorHex(color, showAlpha)
                                    dirty = false
                                    true
                                }
                                else -> false
                            }
                    },
                enabled = enabled,
                isError = dirty && parseColorHex(hex, showAlpha, color.alpha) == null,
            )
        }
    }
}

/**
 * The HSV the picker chose last. A color keeps 8 bits per channel, so reading the HSV back from it would move the
 * pointer and the next step, the more the darker the color. The picker shows its own choice until the value changes to
 * a color it did not choose; a host that applies changes later still returns the earlier choices first.
 */
private class Choice {
    var hsv by mutableStateOf<FloatArray?>(null)
        private set

    // The value the choice stands for, and the chosen colors not yet returned as the value, oldest first. Both change
    // only with hsv or the value, so they need no state of their own.
    private var confirmed = Color.Unspecified
    private var pending = emptyList<Color>()

    fun represents(value: Color) = value == confirmed || value in pending

    fun choose(picked: FloatArray, value: Color, next: Color) {
        if (pending.isEmpty()) confirmed = value
        hsv = picked
        if (next != value) pending = (pending + next).takeLast(PENDING_CHOICES)
    }

    /** Called with each value shown: a returned choice confirms it, any other value replaces the choice. */
    fun settle(value: Color) {
        if (value == confirmed) return
        val index = pending.indexOf(value)
        if (index < 0) hsv = null
        confirmed = value
        pending = if (index < 0) emptyList() else pending.drop(index + 1)
    }

    private companion object {
        const val PENDING_CHOICES = 32
    }
}

@Composable
private fun ColorTrack(
    value: Float,
    onChange: (Float) -> Unit,
    label: String,
    colors: List<Color>,
    enabled: Boolean,
    checker: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val theme = OreTheme.colors
    Canvas(
        Modifier.fillMaxWidth()
            .height(13.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                if (enabled)
                    setProgress {
                        onChange(it.coerceIn(0f, 1f))
                        true
                    }
                else disabled()
            }
            .onPreviewKeyEvent {
                if (!enabled || it.type != KeyEventType.KeyDown) false
                else
                    when (it.key) {
                        Key.DirectionLeft,
                        Key.DirectionDown -> {
                            onChange((value - .01f).coerceAtLeast(0f))
                            true
                        }
                        Key.DirectionRight,
                        Key.DirectionUp -> {
                            onChange((value + .01f).coerceAtMost(1f))
                            true
                        }
                        Key.MoveHome -> {
                            onChange(0f)
                            true
                        }
                        Key.MoveEnd -> {
                            onChange(1f)
                            true
                        }
                        else -> false
                    }
            }
            .orePointDrag(enabled) { p, size ->
                focus.requestFocus()
                onChange((p.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            .focusable(enabled)
            .oreOutline(if (focused) theme[OreColors.focus] else null)
    ) {
        if (checker) drawChecker()
        drawRect(Brush.horizontalGradient(colors))
        drawRect(theme[OreColors.edge], style = Stroke(1.dp.toPx()))
        val x = (value * size.width).coerceIn(2.dp.toPx(), (size.width - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
        drawRect(theme[OreColors.edge], Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
        drawRect(
            Color.White,
            Offset(x - 1.dp.toPx(), 1.dp.toPx()),
            Size(2.dp.toPx(), (size.height - 2.dp.toPx()).coerceAtLeast(0f)),
        )
        if (!enabled) drawRect(theme[OreColors.panel].copy(alpha = .55f))
    }
}

@Composable
private fun Modifier.orePointDrag(enabled: Boolean, onPoint: (Offset, IntSize) -> Unit): Modifier {
    val latest by rememberUpdatedState(onPoint)
    return pointerInput(enabled) {
        if (enabled)
            awaitEachGesture {
                val down = awaitFirstDown()
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                down.consume()
                latest(down.position, size)
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    latest(change.position, size)
                    change.consume()
                } while (true)
            }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChecker() {
    val cell = 5.dp.toPx().coerceAtLeast(1f)
    for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) drawRect(
        if ((x + y) % 2 == 0) Color(0xFFB8BABD) else Color(0xFF58595A),
        Offset(x * cell, y * cell),
        Size(min(cell, size.width - x * cell), min(cell, size.height - y * cell)),
    )
}

private fun colorHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val delta = high - low
    val h =
        when {
            delta == 0f -> 0f
            high == r -> 60f * ((g - b) / delta % 6f)
            high == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
    return floatArrayOf(if (h < 0) h + 360f else h, if (high == 0f) 0f else delta / high, high)
}

private fun colorHex(color: Color, alpha: Boolean): String {
    val argb = color.toArgb()
    return "#" +
        if (alpha) java.lang.String.format(java.util.Locale.ROOT, "%08X", (argb shl 8) or (argb ushr 24))
        else java.lang.String.format(java.util.Locale.ROOT, "%06X", argb and 0xFFFFFF)
}

private fun parseColorHex(text: String, alpha: Boolean, oldAlpha: Float): Color? {
    val digits = text.removePrefix("#")
    if (digits.length != (if (alpha) 8 else 6)) return null
    val bits = digits.toLongOrNull(16) ?: return null
    return if (alpha) Color(((bits ushr 8) or ((bits and 255) shl 24)).toInt())
    else Color(bits.toInt() or 0xFF000000.toInt()).copy(alpha = oldAlpha)
}
