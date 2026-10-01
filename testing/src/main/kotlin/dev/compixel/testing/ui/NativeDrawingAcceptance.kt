package dev.compixel.testing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs

const val DRAWING_BACKGROUND = 0xFF203040.toInt()
const val DRAWING_RED = 0xFFE84030.toInt()
const val DRAWING_GREEN = 0xFF30D070.toInt()
const val DRAWING_BLUE = 0xFF3070D0.toInt()
val DRAWING_TICKING = listOf(0xFF3060E0.toInt(), 0xFFE0C030.toInt())

class NativeDrawingVisualModel {
    var expanded by mutableStateOf(false)
    var visible by mutableStateOf(true)
    private val positions = mutableMapOf<String, Rect>()

    fun bounds() = positions.toMap()

    internal fun record(name: String) = Modifier.onGloballyPositioned { positions[name] = it.boundsInRoot() }
}

@Composable
fun NativeDrawingVisualScene(
    model: NativeDrawingVisualModel,
    still: @Composable (Modifier) -> Unit,
    ticking: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(DRAWING_BACKGROUND))) {
        if (model.visible) {
            still(
                Modifier.offset(12.dp, 12.dp)
                    .size(if (model.expanded) 205.5.dp else 180.5.dp, if (model.expanded) 60.25.dp else 48.25.dp)
                    .then(model.record("wide"))
            )
            still(Modifier.offset(250.dp, 12.dp).size(36.5.dp, 104.25.dp).then(model.record("portrait")))
            Box(
                Modifier.offset(12.dp, 100.dp)
                    .size(100.dp, 60.dp)
                    .then(model.record("clipped"))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                still(Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f })
            }
            preview(Modifier.offset(130.dp, 84.dp).size(88.dp, 56.dp).then(model.record("preview")))
            ticking(Modifier.offset(140.dp, 150.dp).size(110.dp, 28.dp).then(model.record("ticking")))
        }
    }
}

fun verifyNativeDrawingPixels(bounds: Map<String, Rect>, width: Int, height: Int, pixel: (Int, Int) -> Int) {
    fun sample(name: String, x: Float, y: Float): Int {
        val area = checkNotNull(bounds[name]) { "Missing native drawing $name" }
        val px = (area.left + area.width * x).toInt()
        val py = (area.top + area.height * y).toInt()
        check(px in 0 until width && py in 0 until height) { "Native drawing sample outside frame: $name" }
        return pixel(px, py)
    }
    fun delta(a: Int, b: Int) = listOf(0, 8, 16, 24).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
    fun expect(name: String, x: Float, y: Float, color: Int) {
        val actual = sample(name, x, y)
        check(delta(actual, color) <= 8) {
            "$name ($x,$y): ${Integer.toHexString(actual)}, expected ${Integer.toHexString(color)}"
        }
    }
    for (name in listOf("wide", "portrait")) {
        expect(name, 0.25f, 0.5f, DRAWING_GREEN)
        expect(name, 0.6f, 0.5f, DRAWING_RED)
        expect(name, 0.6f, 0.03f, DRAWING_RED)
        expect(name, 0.9f, 0.95f, DRAWING_BLUE)
    }
    val preview = checkNotNull(bounds["preview"])
    var previewPixels = 0
    for (y in preview.top.toInt() until preview.bottom.toInt()) {
        for (x in preview.left.toInt() until preview.right.toInt()) {
            if (delta(pixel(x, y), DRAWING_BACKGROUND) > 24) previewPixels++
        }
    }
    check(previewPixels >= 36) { "Native entity preview did not render: $previewPixels pixels" }
    expect("clipped", 0.01f, 0.01f, DRAWING_BACKGROUND)
    var blended = 0xFF000000.toInt()
    for (shift in listOf(0, 8, 16)) blended =
        blended or (((((DRAWING_RED ushr shift) and 255) + ((DRAWING_BACKGROUND ushr shift) and 255)) / 2) shl shift)
    expect("clipped", 0.6f, 0.5f, blended)
    check(DRAWING_TICKING.any { delta(sample("ticking", 0.5f, 0.5f), it) <= 8 }) {
        "Native ticking rectangle has no complete frame"
    }
}
