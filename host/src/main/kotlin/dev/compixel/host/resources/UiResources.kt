package dev.compixel.host.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.compixel.platform.ResourceSource
import dev.compixel.platform.UiThread
import java.io.FileNotFoundException
import org.jetbrains.skia.Image

/**
 * A host's resource files, shared by its screens and HUD layers. Images are decoded once while any composition uses
 * them. [reload] makes their painters read the new files without replacing the compositions. All access belongs to
 * [thread]; the decoded bitmaps are CPU images, whose pixel storage remains alive with their painters and recorded
 * frames. They do not borrow the game's textures or need a GPU context of their own.
 */
class UiResources(
    private val source: ResourceSource,
    private val thread: UiThread = UiThread.current(),
    private val onError: (String, Exception) -> Unit = { _, _ -> },
) {
    private class Entry(val image: ImageBitmap, var users: Int = 0)

    private val images = HashMap<String, Entry>()
    private val revision = mutableLongStateOf(0)
    private val missing by lazy { missingImage() }

    internal val cachedImages: Int
        get() = images.size

    /** The host successfully reloaded its resource packs; existing painters update on their next composition. */
    fun reload() {
        thread.check()
        images.clear()
        revision.longValue++
    }

    @Composable
    internal fun painter(path: String, filterQuality: FilterQuality): Painter {
        val lease = remember(this, revision.longValue, path) { acquire(path) }
        return remember(lease, filterQuality) { BitmapPainter(lease.image, filterQuality = filterQuality) }
    }

    private fun acquire(path: String): Lease {
        thread.check()
        val entry = images.getOrPut(path) { Entry(load(path)) }
        entry.users++
        return Lease(path, entry)
    }

    private fun load(path: String): ImageBitmap =
        try {
            val bytes = source.read(path) ?: throw FileNotFoundException(path)
            // Compose 1.12 copies the decoded raster into its own CPU bitmap. Close only the temporary decoder image;
            // the bitmap's pixel storage is retained by Compose and Skia pictures for as long as they draw it.
            Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
        } catch (error: Exception) {
            onError(path, error)
            missing
        }

    private inner class Lease(private val path: String, private val entry: Entry) : RememberObserver {
        val image: ImageBitmap
            get() = entry.image

        override fun onRemembered() = Unit

        override fun onForgotten() = release()

        override fun onAbandoned() = release()

        private fun release() {
            thread.check()
            if (--entry.users == 0 && images[path] === entry) images.remove(path)
        }
    }
}

/** The host's resources. Minecraft screens and HUD layers provide these automatically, also to popups and dialogs. */
val LocalResources = staticCompositionLocalOf<UiResources> { error("This host does not provide UI resources") }

/**
 * A raster image from the host's resource system, ready for Compose's Image or Modifier.paint. Resource-pack priority
 * and reloads follow the host automatically. [path] is the complete resource path, including its file extension;
 * Minecraft uses names such as `example:textures/gui/panel.png`. Missing or undecodable files show a checkerboard.
 * [filterQuality] defaults to nearest-neighbour sampling so pixel art stays crisp when scaled.
 */
@Composable
fun painterResource(path: String, filterQuality: FilterQuality = FilterQuality.None): Painter =
    LocalResources.current.painter(path, filterQuality)

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
