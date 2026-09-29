package dev.compixel.render.gl.testing

import dev.compixel.render.RecordedFrame
import dev.compixel.render.UiFrameProfiler
import dev.compixel.render.gl.*
import dev.compixel.testing.render.RendererPixels
import dev.compixel.testing.render.RendererProbeResult
import kotlin.concurrent.thread
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryUtil

/** Explicit real-context regression probe. Never called by the normal rendering path. */
object OpenGlRendererProbe {
    fun verify(frame: RecordedFrame, expectedRgba: ByteArray): RendererProbeResult {
        val width = frame.viewport.width
        val height = frame.viewport.height
        require(expectedRgba.size == width * height * 4)
        val original = GlStateSnapshot.capture(glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
        val drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS)
        var targetTexture = 0
        var targetFbo = 0
        var hostVao = 0
        var hostBuffer = 0
        var pixels = ByteArray(0)
        var worstPixels = 0
        var worstMean = 0.0
        val cycles = 12
        try {
            GlStateSnapshot.preparePixelTransfers()
            glActiveTexture(GL_TEXTURE0)
            targetTexture = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, targetTexture)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            targetFbo = glGenFramebuffers()
            glBindFramebuffer(GL_FRAMEBUFFER, targetFbo)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
            hostVao = glGenVertexArrays()
            glBindVertexArray(hostVao)
            hostBuffer = glGenBuffers()
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, hostBuffer)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, 4096L, GL_STATIC_DRAW)
            repeat(cycles) { cycle ->
                OpenGlFrameRenderer(verifyState = true).use { renderer ->
                    repeat(3) { iteration ->
                        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo)
                        glDisable(GL_SCISSOR_TEST)
                        glDisable(GL_FRAMEBUFFER_SRGB)
                        glColorMask(true, true, true, true)
                        glClearColor(0f, 0f, 0f, 0f)
                        glClear(GL_COLOR_BUFFER_BIT)
                        // Simulate MC atlas transfers and a foreign GUI pass, including a bound PBO.
                        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, hostBuffer)
                        glPixelStorei(GL_UNPACK_ALIGNMENT, 8)
                        glPixelStorei(GL_UNPACK_ROW_LENGTH, width + 19)
                        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 3)
                        glPixelStorei(GL_UNPACK_SKIP_ROWS, 5)
                        glEnable(GL_SCISSOR_TEST)
                        glScissor(3, 5, width - 9, height - 11)
                        glEnable(GL_DEPTH_TEST)
                        glDepthMask(false)
                        glEnable(GL_CULL_FACE)
                        glCullFace(GL_FRONT)
                        glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT, GL_FUNC_ADD)
                        glBlendFuncSeparate(GL_SRC_ALPHA, GL_DST_ALPHA, GL_ZERO, GL_ONE)
                        glColorMask(false, true, false, false)
                        // RenderPearl caches these per draw buffer, including currently unused ones.
                        // Global glEnable/glColorMask restoration must not flatten distinct values.
                        for (index in 0 until drawBuffers) {
                            if (index % 2 == iteration % 2) glEnablei(GL_BLEND, index) else glDisablei(GL_BLEND, index)
                            glColorMaski(index, index % 2 == 0, true, false, index % 2 != 0)
                        }
                        glActiveTexture(GL_TEXTURE3)
                        renderer.render(frame)
                        renderer.present(OpenGlDestination(targetFbo, width, height))
                        for (index in 0 until drawBuffers) {
                            check(glIsEnabledi(GL_BLEND, index) == (index % 2 == iteration % 2)) {
                                "Host blend enable changed for draw buffer $index"
                            }
                            val mask = IntArray(4).also { glGetIntegeri_v(GL_COLOR_WRITEMASK, index, it) }
                            check(
                                mask.contentEquals(
                                    intArrayOf(if (index % 2 == 0) 1 else 0, 1, 0, if (index % 2 != 0) 1 else 0)
                                )
                            ) {
                                "Host color mask changed for draw buffer $index"
                            }
                        }
                        if (iteration != 1) {
                            // A recorded image must survive overwriting its source and retiring the cache reference.
                            val copied = renderer.copyFramebuffer(OpenGlDestination(targetFbo, width, height))
                            val cropped =
                                if (iteration == 0)
                                    renderer.copyFramebuffer(
                                        OpenGlDestination(targetFbo, width, height),
                                        width / 2,
                                        height / 2,
                                    )
                                else
                                    readFramebufferImage(
                                        OpenGlDestination(targetFbo, width, height),
                                        width / 2,
                                        height / 2,
                                    )
                            val picture =
                                PictureRecorder().use { recorder ->
                                    val canvas = recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat()))
                                    canvas.drawImageRect(copied, Rect.makeWH(width.toFloat(), height.toFloat()))
                                    // Replacing the top-left region must preserve its pixels in both import paths.
                                    Paint().use { paint ->
                                        paint.blendMode = BlendMode.SRC
                                        canvas.drawImageRect(cropped, Rect.makeWH(width / 2f, height / 2f), paint)
                                    }
                                    recorder.finishRecordingAsPicture()
                                }
                            if (iteration == 0) renderer.releaseImages(listOf(copied, cropped))
                            else {
                                renderer.releaseImage(copied)
                                cropped.close()
                            }
                            // The picture still refers to the released copies, so they wait in retirement.
                            val released = renderer.statistics
                            check(
                                released.retiredNativeImages == (if (iteration == 0) 2 else 1) &&
                                    released.liveNativeImages == released.retiredNativeImages
                            ) {
                                "Released images still referenced by a picture were not retired: $released"
                            }
                            glColorMask(true, true, true, true)
                            glDisable(GL_SCISSOR_TEST)
                            glClearColor(0f, 0f, 0f, 0f)
                            glClear(GL_COLOR_BUFFER_BIT)
                            RecordedFrame(frame.generation, frame.viewport, picture).use { imported ->
                                renderer.render(imported)
                                renderer.present(OpenGlDestination(targetFbo, width, height))
                            }
                        }
                        check(glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING) == hostBuffer) { "Host VAO was modified" }
                        check(glIsTexture(targetTexture)) { "Borrowed destination texture was deleted" }
                        check(glGetError() == GL_NO_ERROR) { "GL error during renderer probe" }
                        pixels = readRgba(targetFbo, width, height)
                        val difference =
                            RendererPixels.verify(expectedRgba, pixels, "CPU/GL cycle $cycle, iteration $iteration")
                        worstPixels = maxOf(worstPixels, difference.differentPixels)
                        worstMean = maxOf(worstMean, difference.meanChannelError)
                        check(renderer.statistics.fullFrameUploads == 0L)
                        if (iteration == 1) {
                            renderer.reset()
                            check(renderer.needsFrame && renderer.statistics.liveSurfaces == 0)
                        }
                    }
                    check(renderer.statistics.surfaceAllocations == 2L) { "Retained surface was not reused" }
                }
            }
            verifyRetirement(frame, targetFbo)
            // A host that composites through its own pipeline never calls present(); polling alone must deliver
            // timings.
            val profiler = UiFrameProfiler()
            OpenGlFrameRenderer(verifyState = true, profiler = profiler).use { renderer ->
                profiler.beginFrame()
                renderer.render(frame)
                profiler.endFrame()
                // Probe-only wait, so the non-blocking poll is certain to find the timestamps available.
                glFinish()
                profiler.beginFrame()
                renderer.collectGpuTimings()
                profiler.endFrame()
                check(profiler.frames().first().missingGpuResults == 0) { "GPU timings stayed pending without present" }
            }
            return RendererProbeResult(pixels, cycles, worstPixels, worstMean)
        } finally {
            glBindVertexArray(0)
            if (hostBuffer != 0) glDeleteBuffers(hostBuffer)
            if (hostVao != 0) glDeleteVertexArrays(hostVao)
            if (targetFbo != 0) glDeleteFramebuffers(targetFbo)
            if (targetTexture != 0) glDeleteTextures(targetTexture)
            original.restore()
            original.assertRestored()
        }
    }

    /**
     * Compose records imported images on its own thread and may drop those pictures after the renderer has released the
     * image. The final release must still happen on this thread; a thread without the context would lose the texture.
     */
    private fun verifyRetirement(frame: RecordedFrame, framebuffer: Int) {
        val width = frame.viewport.width
        val height = frame.viewport.height
        val textures = liveTextures()
        OpenGlFrameRenderer(verifyState = true).use { renderer ->
            repeat(3) {
                val image = renderer.copyFramebuffer(OpenGlDestination(framebuffer, width, height))
                val picture =
                    PictureRecorder().use { recorder ->
                        recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat())).drawImage(image, 0f, 0f)
                        recorder.finishRecordingAsPicture()
                    }
                renderer.releaseImage(image)
                check(!image.isClosed && renderer.statistics.retiredNativeImages == 1) {
                    "An image was closed while a picture still referred to it"
                }
                thread { picture.close() }.join()
                renderer.render(frame)
                check(image.isClosed && renderer.statistics.liveNativeImages == 0) {
                    "An unreferenced retired image stayed open: ${renderer.statistics}"
                }
            }
        }
        val remaining = liveTextures()
        check(remaining == textures) { "Retired images left ${remaining - textures} GL textures behind" }
    }

    /** Scans past a fresh texture name: drivers hand out names upwards and reuse freed ones below it. */
    private fun liveTextures(): Int {
        val fresh = glGenTextures()
        glDeleteTextures(fresh)
        return (1..fresh + 4096).count { glIsTexture(it) }
    }

    private fun readRgba(framebuffer: Int, width: Int, height: Int): ByteArray {
        GlStateSnapshot.preparePixelTransfers()
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer)
        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            return ByteArray(buffer.capacity()) { index ->
                val row = index / (width * 4)
                buffer.get((height - 1 - row) * width * 4 + index % (width * 4))
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }
}
