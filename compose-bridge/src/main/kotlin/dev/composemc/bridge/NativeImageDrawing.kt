package dev.composemc.bridge

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Image

/**
 * Record an immutable Skia image without Image.toComposeImageBitmap(), which copies to a CPU
 * Bitmap in Compose 1.12. A Compose saveLayer applies the canvas alpha multiplier even under
 * ModulateAlpha; the native draw inherits the current transform and clipping.
 */
fun DrawScope.drawNativeImage(image: Image, layerPaint: Paint) {
    ComposeThread.check()
    val info = image.imageInfo
    val scale = minOf(size.width / info.width, size.height / info.height)
    if (scale <= 0f) return
    val width = info.width * scale
    val height = info.height * scale
    drawIntoCanvas { canvas ->
        // SkiaBackedCanvas applies its alpha multiplier to this Paint in-place. Reset the
        // base color (not Paint.alpha, which also applies the old multiplier) on every draw.
        layerPaint.color = Color.Black
        canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
        try {
            canvas.skiaCanvas.drawImageRect(image, org.jetbrains.skia.Rect.makeXYWH(
                (size.width - width) / 2, (size.height - height) / 2, width, height))
        } finally { canvas.restore() }
    }
}
