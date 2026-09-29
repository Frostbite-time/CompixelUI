package dev.compixel.testing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** A renderer-independent acceptance scene; adapters supply one native drawing handle. */
class NativeItemVisualModel {
    private val positions = mutableMapOf<String, Rect>()

    fun bounds(): Map<String, Rect> = positions.toMap()

    internal fun record(name: String): Modifier = Modifier.onGloballyPositioned { positions[name] = it.boundsInRoot() }
}

const val NATIVE_VISUAL_RED: Int = 0xFFE83B2B.toInt()
private val background = Color(0xFF314055)
private val blue = Color(0xFF286CD0)

@Composable
fun NativeItemVisualScene(model: NativeItemVisualModel, item: @Composable (Modifier) -> Unit) {
    @Composable
    fun cell(name: String, content: @Composable () -> Unit) {
        Box(Modifier.size(64.dp).background(background).then(model.record(name))) { content() }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF172023)).padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            cell("base") {}
            cell("opaque") { item(Modifier.fillMaxSize()) }
            cell("hidden") { item(Modifier.fillMaxSize().graphicsLayer { alpha = 0f }) }
            cell("half") { item(Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            cell("rotated") { item(Modifier.fillMaxSize().graphicsLayer { rotationZ = 45f }) }
            cell("clipped") {
                Box(Modifier.fillMaxSize().clip(CircleShape)) { item(Modifier.fillMaxSize()) }
            }
            cell("covered") {
                item(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(blue))
            }
            cell("blue") { Box(Modifier.fillMaxSize().background(blue)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            cell("duplicate-left") { item(Modifier.fillMaxSize()) }
            cell("duplicate-right") { item(Modifier.fillMaxSize()) }
        }
    }
}

/** Sample stable interiors, allowing a few color levels for backend rounding. */
fun verifyNativeItemVisualPixels(bounds: Map<String, Rect>, width: Int, height: Int, pixel: (Int, Int) -> Int) {
    fun sample(name: String, x: Float = 0.5f, y: Float = 0.5f): Int {
        val area = checkNotNull(bounds[name]) { "Missing native visual bounds: $name" }
        val px = (area.left + area.width * x).toInt()
        val py = (area.top + area.height * y).toInt()
        check(px in 0 until width && py in 0 until height) { "Native visual sample outside frame: $name" }
        return pixel(px, py)
    }
    fun delta(a: Int, b: Int): Int =
        (0..2).maxOf { channel ->
            abs((a ushr (channel * 8) and 255) - (b ushr (channel * 8) and 255))
        }
    val base = sample("base")
    val opaque = sample("opaque")
    val half = sample("half")
    check(delta(base, opaque) > 40) { "Native drawing is missing" }
    check(delta(sample("hidden"), base) <= 3) { "Native icon ignored Compose alpha=0" }
    check(delta(half, base) > 10 && delta(half, opaque) > 10) { "Native icon ignored Compose alpha=0.5" }
    check(delta(sample("rotated"), opaque) <= 8) { "Rotated native icon lost its center" }
    check(delta(sample("rotated", 0.04f, 0.04f), base) <= 5) { "Native icon ignored rotation" }
    check(delta(sample("clipped"), opaque) <= 8) { "Clipped native icon lost its center" }
    check(delta(sample("clipped", 0.04f, 0.04f), base) <= 5) { "Native icon ignored nonrectangular clipping" }
    check(delta(sample("covered"), sample("blue")) <= 3) { "Native icon appeared above later Compose content" }
    check(delta(sample("duplicate-left"), opaque) <= 8 && delta(sample("duplicate-right"), opaque) <= 8) {
        "A repeated native handle lost one of its placements"
    }
}
