package dev.composemc.testing.render

import dev.composemc.render.RecordedFrame
import java.io.File
import kotlin.math.abs
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

data class PixelDifference(val differentPixels: Int, val meanChannelError: Double)

data class RendererProbeResult(
    val rgba: ByteArray,
    val cycles: Int,
    val worstDifferentPixels: Int,
    val worstMeanChannelError: Double,
)

/** Both backends compare top-left, premultiplied RGBA8 pixels against the same CPU raster. */
object RendererPixels {
    fun reference(frame: RecordedFrame): ByteArray {
        val info = imageInfo(frame.viewport.width, frame.viewport.height)
        return Surface.makeRasterN32Premul(info.width, info.height).use { surface ->
            surface.canvas.clear(0)
            frame.draw(surface.canvas)
            surface.makeImageSnapshot().use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocPixels(info))
                    check(image.readPixels(bitmap))
                    checkNotNull(bitmap.readPixels(info))
                }
            }
        }
    }

    fun verify(expected: ByteArray, actual: ByteArray, context: String): PixelDifference {
        require(expected.isNotEmpty() && expected.size % 4 == 0 && actual.size == expected.size)
        var different = 0
        var totalError = 0L
        for (pixel in expected.indices step 4) {
            var maximum = 0
            for (channel in 0..3) {
                val index = pixel + channel
                val delta = abs((actual[index].toInt() and 255) - (expected[index].toInt() and 255))
                totalError += delta
                maximum = maxOf(maximum, delta)
            }
            if (maximum > 16) different++
        }
        val mean = totalError.toDouble() / actual.size
        check(different <= actual.size / 4 / 50 && mean < 2.0) {
            "$context: $different different pixels, mean channel error $mean"
        }
        return PixelDifference(different, mean)
    }

    fun save(file: File, rgba: ByteArray, width: Int, height: Int) {
        Image.makeRaster(imageInfo(width, height), rgba, width * 4).use { image ->
            checkNotNull(image.encodeToData()).use { file.writeBytes(it.bytes) }
        }
    }

    private fun imageInfo(width: Int, height: Int) =
        ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
}
