package dev.composemc.bridge

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaPaint
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

/**
 * Record [source], a pixel region of an immutable Skia image, fitted like [drawNativeImage] but
 * without an offscreen layer, so many regions of one shared image stay cheap to draw. The
 * canvas alpha multiplier reaches the image through [paint]; nearest sampling stays inside [source].
 */
fun DrawScope.drawNativeImageRegion(image: Image, source: org.jetbrains.skia.Rect, paint: Paint) {
    ComposeThread.check()
    val scale = minOf(size.width / source.width, size.height / source.height)
    if (scale <= 0f) return
    val width = source.width * scale
    val height = source.height * scale
    drawIntoCanvas { canvas ->
        // SkiaBackedCanvas applies its alpha multiplier to a Compose Paint in place on each draw.
        // An empty rectangle applies it without output; reset the base color every time.
        paint.color = Color.Black
        canvas.drawRect(0f, 0f, 0f, 0f, paint)
        canvas.skiaCanvas.drawImageRect(image, source, org.jetbrains.skia.Rect.makeXYWH(
            (size.width - width) / 2, (size.height - height) / 2, width, height), paint.skiaPaint)
    }
}
