package dev.composemc.render

import dev.composemc.platform.Viewport
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Picture
import org.jetbrains.skia.Surface

/** The caller owns this lease; replay and close must not run concurrently. */
class RecordedFrame(
    val generation: Long,
    val viewport: Viewport,
    private val picture: Picture,
) : AutoCloseable {
    private var closed = false

    fun draw(canvas: Canvas) {
        check(!closed) { "Frame $generation has been released" }
        canvas.drawPicture(picture)
    }

    /** Debug bridge for hosts without a shared GPU surface yet. It is intentionally a readback. */
    fun encodePng(): ByteArray {
        check(!closed) { "Frame $generation has been released" }
        Surface.makeRasterN32Premul(viewport.width, viewport.height).use { surface ->
            surface.canvas.clear(0)
            draw(surface.canvas)
            surface.makeImageSnapshot().use { image ->
                return checkNotNull(image.encodeToData()).use { data -> data.bytes }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        picture.close()
    }
}
