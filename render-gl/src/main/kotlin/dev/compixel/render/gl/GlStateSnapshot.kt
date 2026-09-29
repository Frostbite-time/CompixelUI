package dev.compixel.render.gl

import org.lwjgl.opengl.EXTBlendColor.GL_BLEND_COLOR_EXT
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*

/**
 * Raw GL only inside the isolation scope. Restoring actual state also preserves Minecraft's cached state, because no
 * Minecraft/RenderSystem state setter is called inside that scope. This boundary supports the single-color-target, GL
 * 3.2+ GUI pass, not arbitrary MRT passes.
 */
internal class GlStateSnapshot private constructor(private val textureUnits: Int) {
    private val samplerObjects = GL.getCapabilities().let { it.OpenGL33 || it.GL_ARB_sampler_objects }
    private val program = glGetInteger(GL_CURRENT_PROGRAM)
    private val vao = glGetInteger(GL_VERTEX_ARRAY_BINDING)
    private val arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING)
    private val drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
    private val readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
    private val renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING)
    private val packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING)
    private val unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)
    private val viewport = ints(GL_VIEWPORT, 4)
    private val scissor = ints(GL_SCISSOR_BOX, 4)
    private val activeTexture = glGetInteger(GL_ACTIVE_TEXTURE)
    private val textures = IntArray(textureUnits)
    private val samplers = IntArray(textureUnits)
    private val enabled = toggles.associateWith(::glIsEnabled)
    private val blend = blendParameters.map(::glGetInteger).toIntArray()
    private val blendColor = floats(GL_BLEND_COLOR_EXT, 4)
    private val colorMask = ints(GL_COLOR_WRITEMASK, 4)
    // Global setters broadcast to every draw buffer, even with a single attachment bound.
    // Preserve indexed state too: RenderPearl caches blend enables and write masks per buffer.
    private val drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS)
    private val blendEnabled = BooleanArray(drawBuffers) { glIsEnabledi(GL_BLEND, it) }
    private val colorMasks =
        Array(drawBuffers) { index ->
            IntArray(4).also { glGetIntegeri_v(GL_COLOR_WRITEMASK, index, it) }
        }
    private val depthMask = glGetBoolean(GL_DEPTH_WRITEMASK)
    private val depthFunc = glGetInteger(GL_DEPTH_FUNC)
    private val depthRange = DoubleArray(2).also { glGetDoublev(GL_DEPTH_RANGE, it) }
    private val frontFace = glGetInteger(GL_FRONT_FACE)
    private val cullFace = glGetInteger(GL_CULL_FACE_MODE)
    private val polygonMode = ints(GL_POLYGON_MODE, 2)
    private val polygonOffset = floatArrayOf(glGetFloat(GL_POLYGON_OFFSET_FACTOR), glGetFloat(GL_POLYGON_OFFSET_UNITS))
    private val lineWidth = glGetFloat(GL_LINE_WIDTH)
    private val logicOp = glGetInteger(GL_LOGIC_OP_MODE)
    private val stencil = stencilParameters.map(::glGetInteger).toIntArray()
    private val stencilBack = stencilBackParameters.map(::glGetInteger).toIntArray()
    private val pixelStore = pixelParameters.map(::glGetInteger).toIntArray()
    private val clearColor = floats(GL_COLOR_CLEAR_VALUE, 4)
    private val clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE)
    private val clearStencil = glGetInteger(GL_STENCIL_CLEAR_VALUE)
    private val sampleCoverage = glGetFloat(GL_SAMPLE_COVERAGE_VALUE)
    private val sampleInvert = glGetBoolean(GL_SAMPLE_COVERAGE_INVERT)
    private val sampleMask = glGetIntegeri(GL_SAMPLE_MASK_VALUE, 0)
    private val restartIndex = glGetInteger(GL_PRIMITIVE_RESTART_INDEX)

    init {
        for (unit in 0 until textureUnits) {
            glActiveTexture(GL_TEXTURE0 + unit)
            textures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D)
            if (samplerObjects) samplers[unit] = glGetInteger(GL_SAMPLER_BINDING)
        }
        glActiveTexture(activeTexture)
    }

    fun restore() {
        glUseProgram(program)
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer)
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
        for (unit in 0 until textureUnits) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, textures[unit])
            if (samplerObjects) glBindSampler(unit, samplers[unit])
        }
        glActiveTexture(activeTexture)
        enabled.forEach { (cap, value) -> if (value) glEnable(cap) else glDisable(cap) }
        glBlendFuncSeparate(blend[0], blend[1], blend[2], blend[3])
        glBlendEquationSeparate(blend[4], blend[5])
        glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3])
        glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0)
        for (index in 0 until drawBuffers) {
            if (blendEnabled[index]) glEnablei(GL_BLEND, index) else glDisablei(GL_BLEND, index)
            val mask = colorMasks[index]
            glColorMaski(index, mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0)
        }
        glDepthMask(depthMask)
        glDepthFunc(depthFunc)
        glDepthRange(depthRange[0], depthRange[1])
        glFrontFace(frontFace)
        glCullFace(cullFace)
        glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0])
        glPolygonOffset(polygonOffset[0], polygonOffset[1])
        glLineWidth(lineWidth)
        glLogicOp(logicOp)
        restoreStencil(GL_FRONT, stencil)
        restoreStencil(GL_BACK, stencilBack)
        pixelParameters.forEachIndexed { index, parameter -> glPixelStorei(parameter, pixelStore[index]) }
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        glClearDepth(clearDepth)
        glClearStencil(clearStencil)
        glSampleCoverage(sampleCoverage, sampleInvert)
        glSampleMaski(0, sampleMask)
        glPrimitiveRestartIndex(restartIndex)
    }

    /** Expensive diagnostic assertion, enabled by the real-context integration probe only. */
    fun assertRestored() {
        val actual = capture(textureUnits).values()
        val changed = values().filter { (name, value) -> value != actual[name] }.keys
        check(changed.isEmpty()) { "OpenGL state was not restored: " + changed.joinToString() }
    }

    private fun values(): Map<String, Any> =
        linkedMapOf(
            "program" to program,
            "vao" to vao,
            "arrayBuffer" to arrayBuffer,
            "drawFbo" to drawFbo,
            "readFbo" to readFbo,
            "renderbuffer" to renderbuffer,
            "packBuffer" to packBuffer,
            "unpackBuffer" to unpackBuffer,
            "viewport" to viewport.toList(),
            "scissor" to scissor.toList(),
            "activeTexture" to activeTexture,
            "textures" to textures.toList(),
            "samplers" to samplers.toList(),
            "enabled" to enabled,
            "blend" to blend.toList(),
            "blendColor" to blendColor.toList(),
            "colorMask" to colorMask.toList(),
            "depthMask" to depthMask,
            "depthFunc" to depthFunc,
            "blendEnabled" to blendEnabled.toList(),
            "colorMasks" to colorMasks.map { it.toList() },
            "depthRange" to depthRange.toList(),
            "frontFace" to frontFace,
            "cullFace" to cullFace,
            "polygonMode" to polygonMode.toList(),
            "polygonOffset" to polygonOffset.toList(),
            "lineWidth" to lineWidth,
            "logicOp" to logicOp,
            "stencil" to stencil.toList(),
            "stencilBack" to stencilBack.toList(),
            "pixelStore" to pixelStore.toList(),
            "clearColor" to clearColor.toList(),
            "clearDepth" to clearDepth,
            "clearStencil" to clearStencil,
            "sampleCoverage" to sampleCoverage,
            "sampleInvert" to sampleInvert,
            "sampleMask" to sampleMask,
            "restartIndex" to restartIndex,
        )

    private fun restoreStencil(face: Int, values: IntArray) {
        glStencilFuncSeparate(face, values[0], values[1], values[2])
        glStencilMaskSeparate(face, values[3])
        glStencilOpSeparate(face, values[4], values[5], values[6])
    }

    companion object {
        fun capture(textureUnits: Int): GlStateSnapshot = GlStateSnapshot(textureUnits)

        fun preparePixelTransfers() {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
            pixelParameters.forEach { parameter ->
                glPixelStorei(
                    parameter,
                    if (parameter == GL_PACK_ALIGNMENT || parameter == GL_UNPACK_ALIGNMENT) 4 else 0,
                )
            }
        }

        private fun ints(parameter: Int, count: Int) = IntArray(count).also { glGetIntegerv(parameter, it) }

        private fun floats(parameter: Int, count: Int) = FloatArray(count).also { glGetFloatv(parameter, it) }

        private val toggles =
            intArrayOf(
                GL_BLEND,
                GL_DEPTH_TEST,
                GL_STENCIL_TEST,
                GL_CULL_FACE,
                GL_SCISSOR_TEST,
                GL_FRAMEBUFFER_SRGB,
                GL_DITHER,
                GL_MULTISAMPLE,
                GL_SAMPLE_ALPHA_TO_COVERAGE,
                GL_SAMPLE_ALPHA_TO_ONE,
                GL_SAMPLE_COVERAGE,
                GL_SAMPLE_MASK,
                GL_RASTERIZER_DISCARD,
                GL_POLYGON_OFFSET_FILL,
                GL_POLYGON_OFFSET_LINE,
                GL_POLYGON_OFFSET_POINT,
                GL_COLOR_LOGIC_OP,
                GL_PRIMITIVE_RESTART,
            )
        private val blendParameters =
            intArrayOf(
                GL_BLEND_SRC_RGB,
                GL_BLEND_DST_RGB,
                GL_BLEND_SRC_ALPHA,
                GL_BLEND_DST_ALPHA,
                GL_BLEND_EQUATION_RGB,
                GL_BLEND_EQUATION_ALPHA,
            )
        private val stencilParameters =
            intArrayOf(
                GL_STENCIL_FUNC,
                GL_STENCIL_REF,
                GL_STENCIL_VALUE_MASK,
                GL_STENCIL_WRITEMASK,
                GL_STENCIL_FAIL,
                GL_STENCIL_PASS_DEPTH_FAIL,
                GL_STENCIL_PASS_DEPTH_PASS,
            )
        private val stencilBackParameters =
            intArrayOf(
                GL_STENCIL_BACK_FUNC,
                GL_STENCIL_BACK_REF,
                GL_STENCIL_BACK_VALUE_MASK,
                GL_STENCIL_BACK_WRITEMASK,
                GL_STENCIL_BACK_FAIL,
                GL_STENCIL_BACK_PASS_DEPTH_FAIL,
                GL_STENCIL_BACK_PASS_DEPTH_PASS,
            )
        private val pixelParameters =
            intArrayOf(
                GL_PACK_ALIGNMENT,
                GL_PACK_ROW_LENGTH,
                GL_PACK_SKIP_PIXELS,
                GL_PACK_SKIP_ROWS,
                GL_PACK_SWAP_BYTES,
                GL_PACK_LSB_FIRST,
                GL_PACK_IMAGE_HEIGHT,
                GL_PACK_SKIP_IMAGES,
                GL_UNPACK_ALIGNMENT,
                GL_UNPACK_ROW_LENGTH,
                GL_UNPACK_SKIP_PIXELS,
                GL_UNPACK_SKIP_ROWS,
                GL_UNPACK_SWAP_BYTES,
                GL_UNPACK_LSB_FIRST,
                GL_UNPACK_IMAGE_HEIGHT,
                GL_UNPACK_SKIP_IMAGES,
            )
    }
}
