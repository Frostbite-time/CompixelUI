package dev.compixel.render.gl

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryUtil

/** Explicit CPU reference path. The normal GL importer uses a GPU copy instead. */
fun readFramebufferImage(source: OpenGlDestination, width: Int = source.width, height: Int = source.height): Image {
    require(width in 1..source.width && height in 1..source.height)
    val state = GlStateSnapshot.capture(glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
    val bytes = MemoryUtil.memAlloc(width * height * 4)
    try {
        GlStateSnapshot.preparePixelTransfers()
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source.framebuffer)
        glReadPixels(0, source.height - height, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        val stride = width * 4
        val rgba =
            ByteArray(bytes.capacity()) { index ->
                bytes.get((height - 1 - index / stride) * stride + index % stride)
            }
        return Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rgba, stride)
    } finally {
        MemoryUtil.memFree(bytes)
        state.restore()
    }
}
