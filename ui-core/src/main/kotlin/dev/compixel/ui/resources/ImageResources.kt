package dev.compixel.ui.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private object ImageResource : ResourceType<ImageBitmap> {
    override fun decode(path: String, bytes: ByteArray): ImageBitmap =
        // Compose copies the raster into its own CPU bitmap. Pictures and painters retain that pixel storage.
        Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
}

private val missing by lazy { missingImage() }

/**
 * A raster image from the host's resources, ready for Compose Image or Modifier.paint. [path] includes its extension,
 * for example `example:textures/gui/panel.png`. Resource priority and reloads follow the host automatically. Missing or
 * undecodable images show a checkerboard. Pixel art uses nearest-neighbour sampling by default.
 */
@Composable
fun painterResource(path: String, filterQuality: FilterQuality = FilterQuality.None): Painter {
    val resources = checkNotNull(LocalResources.current) { "This host does not provide UI resources" }
    val image = resources.load(path, ImageResource).getOrElse { missing }
    return remember(image, filterQuality) { BitmapPainter(image, filterQuality = filterQuality) }
}

private fun missingImage(): ImageBitmap {
    val image = ImageBitmap(16, 16)
    val canvas = Canvas(image)
    val paint = Paint()
    for (y in 0..1) for (x in 0..1) {
        paint.color = if ((x + y) % 2 == 0) Color.Magenta else Color.Black
        canvas.drawRect(x * 8f, y * 8f, (x + 1) * 8f, (y + 1) * 8f, paint)
    }
    return image
}
