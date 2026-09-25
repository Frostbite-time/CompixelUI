package dev.composemc.render.gl

import dev.composemc.render.*
import org.jetbrains.skia.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*

/** These handles belong solely to the GL backend/version adapter, never to Compose or the SPI. */
data class OpenGlDestination(val framebuffer: Int, val width: Int, val height: Int) {
    init { require(framebuffer >= 0 && width > 0 && height > 0) }
}

/** Same-context RGBA8/premultiplied rendering. No pixel readback, PNG or full-frame upload. */
class OpenGlFrameRenderer(
    private val verifyState: Boolean = false,
    private val measureGpu: Boolean = false,
    private val profiler: UiFrameProfiler? = null,
    private val contextIdentity: () -> Any = { GL.getCapabilities() },
) : FrameRenderer<OpenGlDestination> {
    private val owner = Thread.currentThread()
    private val glContext = contextIdentity()
    private val capabilities = GL.getCapabilities()
    private val samplerObjects = capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects
    private val timestampQueries = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query
    private val textureUnits = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)
    private var context: DirectContext? = null
    private var surface: Surface? = null
    private var backendTarget: BackendRenderTarget? = null
    private var texture = 0
    private var framebuffer = 0
    private var stencil = 0
    private var width = 0
    private var height = 0
    private var privateVao = 0
    private var readFramebuffer = 0
    private var compositor = 0
    private var frames = 0L
    private var allocations = 0L
    private var generation = 0L
    private var closed = false
    private var hasFrame = false
    private var renderTimer: GlGpuTimer? = null
    private var presentTimer: GlGpuTimer? = null
    private val nativeTimers = mutableMapOf<GpuPhase, GlGpuTimer>()
    private val importedImages = mutableSetOf<Image>()
    private var imageCopies = 0L
    override val statistics get() = RendererStatistics(RenderBackend.OPENGL, frames, allocations, 0,
        generation, if (surface == null) 0 else 1, renderTimer?.timings?.summary(), presentTimer?.timings?.summary(),
        nativeImageCopies = imageCopies, liveNativeImages = importedImages.size)
    override val needsFrame get() = !hasFrame

    init { check(capabilities.OpenGL32) { "OpenGL 3.2 is required by this backend" } }

    private fun checkContext() {
        check(Thread.currentThread() === owner) { "Renderer accessed outside its owning thread" }
        check(!closed) { "Renderer is closed" }
        check(contextIdentity() == glContext) { "GL context changed; recreate the renderer through the version adapter" }
    }

    private fun <T> isolated(textureUnitCount: Int = textureUnits, action: () -> T): T {
        checkContext()
        // A diagnostic comparison still covers every unit when the production scope is narrower.
        val verification = if (verifyState && textureUnitCount != textureUnits) GlStateSnapshot.capture(textureUnits) else null
        val state = profiler.measureDetail(CpuDetail.GL_CAPTURE) { GlStateSnapshot.capture(textureUnitCount) }
        try {
            if (privateVao == 0) privateVao = glGenVertexArrays()
            // Never let foreign vertex attribute setup mutate Minecraft's currently bound VAO.
            glBindVertexArray(privateVao)
            return action()
        } finally {
            profiler.measureDetail(CpuDetail.GL_RESTORE) { state.restore() }
            if (verifyState) (verification ?: state).assertRestored()
        }
    }

    override fun render(frame: RecordedFrame) = isolated {
        if (timestampQueries && (measureGpu || profiler != null) && renderTimer == null) renderTimer = GlGpuTimer(profiler, GpuPhase.RENDER)
        val timer = renderTimer
        if (timer == null) drawFrame(frame) else timer.measure { drawFrame(frame) }
    }

    /**
     * Copies the retained frame into an adapter-owned RGBA8 texture without a
     * readback or alpha conversion. The destination must have matching dimensions.
     * Its row origin remains OpenGL's bottom-left; the adapter selects its UVs.
     */
    fun copyToTexture(destinationTexture: Int, destinationWidth: Int, destinationHeight: Int) = isolated {
        require(destinationTexture > 0 && destinationTexture != texture)
        check(hasFrame && destinationWidth == width && destinationHeight == height)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer)
        glReadBuffer(GL_COLOR_ATTACHMENT0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, destinationTexture)
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
    }

    /** Copies the top-left region on the GPU. Only the new texture is adopted by Skia. */
    fun copyFramebuffer(source: OpenGlDestination, width: Int = source.width, height: Int = source.height): Image = isolated { profileGpu(GpuPhase.IMAGE_IMPORT) {
        require(width in 1..source.width && height in 1..source.height)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source.framebuffer)
        adoptCopy(source.height, width, height)
    } }

    /**
     * Copies the top-left region of a host-owned RGBA8 texture on the GPU, like [copyFramebuffer].
     * The host texture is attached to a private read framebuffer only for the copy.
     */
    fun copyTexture(sourceTexture: Int, sourceWidth: Int, sourceHeight: Int, width: Int = sourceWidth, height: Int = sourceHeight): Image = isolated { profileGpu(GpuPhase.IMAGE_IMPORT) {
        require(sourceTexture > 0 && sourceTexture != texture)
        require(width in 1..sourceWidth && height in 1..sourceHeight)
        if (readFramebuffer == 0) readFramebuffer = glGenFramebuffers()
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer)
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sourceTexture, 0)
        try {
            check(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Native texture cannot be copied" }
            glReadBuffer(GL_COLOR_ATTACHMENT0)
            adoptCopy(sourceHeight, width, height)
        } finally {
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0)
        }
    } }

    /** Copies from the bound read framebuffer; OpenGL rows start at the bottom. */
    private fun adoptCopy(sourceHeight: Int, width: Int, height: Int): Image {
        val skia = directContext()
        glActiveTexture(GL_TEXTURE0)
        var copy = glGenTextures()
        try {
            glBindTexture(GL_TEXTURE_2D, copy)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 0, sourceHeight - height, width, height, 0)
            skia.resetGLAll()
            return BackendTexture.makeGL(width, height, false, copy, GL_TEXTURE_2D, GL_RGBA8).use { texture ->
                val image = Image.adoptTextureFrom(skia, texture, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888)
                copy = 0
                // Cache immutable metadata on the GL owner before publication to the Compose EDT.
                image.imageInfo
                importedImages += image
                imageCopies++
                image
            }
        } finally { if (copy != 0) glDeleteTextures(copy) }
    }

    /** GPU timestamps describe command-stream intervals, not a hardware busy-time percentage. */
    fun <T> profileGpu(phase: GpuPhase, action: () -> T): T = if (profiler == null || !timestampQueries) action()
        else nativeTimers.getOrPut(phase) { GlGpuTimer(profiler, phase, poolSize = 32) }.measure(action)

    fun releaseImage(image: Image) = releaseImages(listOf(image))

    /** Retire a bounded batch under one state snapshot. Recorded pictures retain their own references. */
    fun releaseImages(images: List<Image>) = isolated {
        images.forEach { image -> if (importedImages.remove(image)) image.close() }
    }

    private fun directContext(): DirectContext = context ?: DirectContext.makeGL().also {
        it.resourceCacheLimit = 64L * 1024 * 1024
        context = it
    }

    private fun drawFrame(frame: RecordedFrame) {
        val skia = directContext()
        if (width != frame.viewport.width || height != frame.viewport.height || surface == null) {
            releaseSurface()
            createSurface(skia, frame.viewport.width, frame.viewport.height)
        }
        val output = checkNotNull(surface)
        skia.resetGLAll()
        // Skia does not own Minecraft's pixel transfer state. In particular, MC atlas
        // uploads leave UNPACK_SKIP_ROWS/PIXELS and ROW_LENGTH set; resetGLAll alone
        // does not normalize every one of these values before Skia uploads glyphs.
        GlStateSnapshot.preparePixelTransfers()
        glDisable(GL_FRAMEBUFFER_SRGB)
        output.canvas.clear(0)
        frame.draw(output.canvas)
        // A single current context orders Skia writes before the following MC composite.
        // No glFinish or CPU fence wait: GL retains deleted objects referenced by queued work.
        skia.flushAndSubmit(output, false)
        hasFrame = true
        generation = frame.generation
        frames++
    }

    /**
     * Hands finished GPU timestamps to the profiler without waiting. [present] does this every frame.
     * A host that composites the frame through its own pipeline must call this once per frame instead;
     * otherwise a phase's latest samples stay pending until that phase runs again, which may be never.
     */
    fun collectGpuTimings() {
        if (profiler == null) return
        checkContext()
        renderTimer?.collect()
        presentTimer?.collect()
        nativeTimers.values.forEach { it.collect() }
    }

    override fun present(destination: OpenGlDestination) {
        checkContext()
        collectGpuTimings()
        if (!hasFrame) return
        // This compositor is our own GL code and binds only texture/sampler unit zero.
        // Skia render/import/retirement scopes continue to capture every available unit.
        isolated(textureUnitCount = 1) {
            if (timestampQueries && (measureGpu || profiler != null) && presentTimer == null) presentTimer = GlGpuTimer(profiler, GpuPhase.PRESENT)
            val timer = presentTimer
            if (timer == null) composite(destination) else timer.measure { composite(destination) }
        }
    }

    private fun composite(destination: OpenGlDestination) {
        if (compositor == 0) compositor = createCompositor()
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination.framebuffer)
        glViewport(0, 0, destination.width, destination.height)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_CULL_FACE)
        glDisable(GL_RASTERIZER_DISCARD)
        glDisable(GL_COLOR_LOGIC_OP)
        glDisable(GL_FRAMEBUFFER_SRGB)
        glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE)
        glDisable(GL_SAMPLE_COVERAGE)
        glDisable(GL_SAMPLE_MASK)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        glColorMask(true, true, true, true)
        glEnable(GL_BLEND)
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(compositor)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        if (samplerObjects) glBindSampler(0, 0)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    override fun reset() = isolated { releaseSurface() }

    private fun createSurface(skia: DirectContext, newWidth: Int, newHeight: Int) {
        val limit = minOf(glGetInteger(GL_MAX_TEXTURE_SIZE), glGetInteger(GL_MAX_RENDERBUFFER_SIZE))
        require(newWidth in 1..limit && newHeight in 1..limit) { "UI dimensions exceed GL limit $limit" }
        try {
            glActiveTexture(GL_TEXTURE0)
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
            texture = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, newWidth, newHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
            framebuffer = glGenFramebuffers()
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
            stencil = glGenRenderbuffers()
            glBindRenderbuffer(GL_RENDERBUFFER, stencil)
            glRenderbufferStorage(GL_RENDERBUFFER, GL_STENCIL_INDEX8, newWidth, newHeight)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencil)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Compose GL framebuffer is incomplete" }
            val target = BackendRenderTarget.makeGL(newWidth, newHeight, 0, 8, framebuffer, GL_RGBA8)
            backendTarget = target
            // Unmanaged RGBA8 matches 1.21.1's GUI color path; do not add a second sRGB conversion.
            surface = checkNotNull(Surface.makeFromBackendRenderTarget(skia, target, SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888, null)) { "Skia could not wrap the Compose framebuffer" }
            width = newWidth
            height = newHeight
            allocations++
        } catch (error: Throwable) { releaseSurface(); throw error }
    }

    private fun releaseSurface() {
        surface?.close(); surface = null
        backendTarget?.close(); backendTarget = null
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer)
        if (stencil != 0) glDeleteRenderbuffers(stencil)
        if (texture != 0) glDeleteTextures(texture)
        framebuffer = 0; stencil = 0; texture = 0
        width = 0; height = 0; hasFrame = false
    }

    override fun close() {
        if (closed) { check(Thread.currentThread() === owner); return }
        val deleted = intArrayOf(texture, framebuffer, stencil, compositor, privateVao, readFramebuffer)
        isolated {
            releaseSurface()
            importedImages.forEach(Image::close)
            importedImages.clear()
            context?.close(); context = null
            if (compositor != 0) glDeleteProgram(compositor)
            if (privateVao != 0) glDeleteVertexArrays(privateVao)
            if (readFramebuffer != 0) glDeleteFramebuffers(readFramebuffer)
            compositor = 0; privateVao = 0; readFramebuffer = 0
            renderTimer?.close()
            presentTimer?.close()
            nativeTimers.values.forEach { it.close() }
            nativeTimers.clear()
        }
        closed = true
        if (verifyState) {
            check(deleted[0] == 0 || !glIsTexture(deleted[0])) { "GL texture survived close" }
            check(deleted[1] == 0 || !glIsFramebuffer(deleted[1])) { "GL framebuffer survived close" }
            check(deleted[2] == 0 || !glIsRenderbuffer(deleted[2])) { "GL stencil survived close" }
            check(deleted[3] == 0 || !glIsProgram(deleted[3])) { "GL program survived close" }
            check(deleted[4] == 0 || !glIsVertexArray(deleted[4])) { "GL vertex array survived close" }
            check(deleted[5] == 0 || !glIsFramebuffer(deleted[5])) { "GL read framebuffer survived close" }
        }
    }

    private fun createCompositor(): Int {
        fun shader(type: Int, source: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, source)
            glCompileShader(shader)
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                val log = glGetShaderInfoLog(shader)
                glDeleteShader(shader)
                error("Compose compositor shader compilation failed: $log")
            }
            return shader
        }
        val vertex = shader(GL_VERTEX_SHADER, """
            #version 150 core
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent())
        var fragment = 0
        var program = 0
        try {
            fragment = shader(GL_FRAGMENT_SHADER, """
                #version 150 core
                uniform sampler2D image;
                in vec2 uv;
                out vec4 color;
                void main() { color = texture(image, uv); }
            """.trimIndent())
            program = glCreateProgram()
            glAttachShader(program, vertex)
            glAttachShader(program, fragment)
            glBindFragDataLocation(program, 0, "color")
            glLinkProgram(program)
            check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) { glGetProgramInfoLog(program) }
            glUseProgram(program)
            glUniform1i(glGetUniformLocation(program, "image"), 0)
            return program
        } catch (error: Throwable) { if (program != 0) glDeleteProgram(program); throw error }
        finally { glDeleteShader(vertex); if (fragment != 0) glDeleteShader(fragment) }
    }
}
