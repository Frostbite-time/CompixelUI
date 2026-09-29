package dev.compixel.testing.render

import dev.compixel.render.RetiredImages
import kotlin.concurrent.thread
import kotlin.test.*
import org.jetbrains.skia.Image
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test

class RetiredImagesTest {
    private fun image(): Image = Surface.makeRasterN32Premul(4, 4).use { it.makeImageSnapshot() }

    private fun record(image: Image): Picture =
        PictureRecorder().use { recorder ->
            recorder.beginRecording(Rect.makeWH(4f, 4f)).drawImage(image, 0f, 0f)
            recorder.finishRecordingAsPicture()
        }

    @Test
    fun `a retired image outlives the pictures that refer to it`() {
        val retired = RetiredImages()
        val image = image()
        val pictures = List(2) { record(image) }
        retired.retire(image)
        retired.release()
        assertFalse(image.isClosed)
        // Compose drops its pictures on its own thread; the final release must still be the renderer's.
        thread { pictures[0].close() }.join()
        retired.release()
        assertFalse(image.isClosed)
        thread { pictures[1].close() }.join()
        assertEquals(1, image.refCount)
        retired.release()
        assertTrue(image.isClosed)
        assertEquals(0, retired.size)
    }

    @Test
    fun `unreferenced and already closed images leave at once`() {
        val retired = RetiredImages()
        val unused = image()
        retired.retire(unused)
        retired.retire(image().also(Image::close))
        assertEquals(1, retired.size)
        val closedElsewhere = image()
        val picture = record(closedElsewhere)
        retired.retire(closedElsewhere)
        closedElsewhere.close()
        retired.release()
        assertTrue(unused.isClosed)
        assertEquals(0, retired.size)
        picture.close()
    }

    @Test
    fun `shutdown closes every image and counts those still referenced`() {
        val retired = RetiredImages()
        val shared = image()
        val picture = record(shared)
        retired.retire(shared)
        retired.retire(image())
        assertEquals(1, retired.releaseAll())
        assertTrue(shared.isClosed)
        assertEquals(0, retired.size)
        // The picture keeps its own reference and still draws.
        Surface.makeRasterN32Premul(4, 4).use { it.canvas.drawPicture(picture) }
        picture.close()
    }
}
