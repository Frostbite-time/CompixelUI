package dev.compixel.render.gl

import org.lwjgl.opengl.ARBImaging.GL_COLOR_TABLE
import org.lwjgl.opengl.EXTBlendColor.GL_BLEND_COLOR_EXT
import org.lwjgl.opengl.EXTWindowRectangles.*
import org.lwjgl.opengl.GL11.GL_INDEX_LOGIC_OP
import org.lwjgl.opengl.GL11.GL_POINT_SMOOTH
import org.lwjgl.opengl.GL11.GL_POLYGON_STIPPLE
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.NVConservativeRaster.GL_CONSERVATIVE_RASTERIZATION_NV

/**
 * GL state that [capture] saves into one int array and [restore] writes back. Each set holds exactly the state one
 * isolation scope can write: restoring more would itself be a write, because GL's global setters reach every draw
 * buffer and viewport. No Minecraft/RenderSystem setter runs inside a scope, so restoring the actual state also keeps
 * Minecraft's cached state valid. Blend functions, viewports and scissor boxes count as single values, as every
 * Minecraft version sets them.
 */
internal class GlStateSet(private val items: List<Item>) {
    private val size = items.sumOf { it.size }

    fun capture(): IntArray {
        val values = IntArray(size)
        var at = 0
        for (item in items) {
            item.read(values, at)
            at += item.size
        }
        return values
    }

    fun restore(values: IntArray) {
        var at = 0
        for (item in items) {
            item.write(values, at)
            at += item.size
        }
    }

    operator fun plus(other: GlStateSet) = GlStateSet(items + other.items)

    abstract class Item(val size: Int) {
        abstract fun read(values: IntArray, at: Int)

        abstract fun write(values: IntArray, at: Int)
    }

    companion object {
        /** What [prepareReadback] and a read into client memory change. */
        val readback =
            GlStateSet(
                listOf(readFramebuffer, packBuffer) +
                    pixelStores(GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_ROWS)
            )
    }
}

/**
 * The state each isolation scope can write on one context. Build it on that context's owner thread.
 *
 * Skia's part covers every write of the Skia m150 GL backend, which both runtime bundles build, on a desktop context
 * and for the 2D textures we hand it. Skia calls GL only through GrGLInterface. It binds textures and samplers on its
 * first min(32, GL_MAX_TEXTURE_IMAGE_UNITS) units, draws with its own vertex array, sets blending and color masks with
 * calls that reach every draw buffer, and uses window rectangles for difference clips. After resetGLAll, its next GL
 * call first runs [skiaReset], wrapping an image included. It never reads pixels back here, has no indirect draws, and
 * ends its timer queries within the flush that started them.
 */
internal class GlScopeStates(capabilities: GLCapabilities) {
    private val samplerObjects = capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects
    private val compatibility = (glGetInteger(GL_CONTEXT_PROFILE_MASK) and GL_CONTEXT_CORE_PROFILE_BIT) == 0
    private val skiaUnits = minOf(32, glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS))
    private val drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS)
    private val windowRectangles =
        if (capabilities.GL_EXT_window_rectangles) glGetInteger(GL_MAX_WINDOW_RECTANGLES_EXT) else 0

    /** Skia's onResetContext. It clears the legacy switches only on a compatibility context. */
    private val skiaReset =
        buildList<GlStateSet.Item> {
            addAll(listOf(depthMask, frontFace, lineWidth))
            addAll(pixelStores(GL_UNPACK_ROW_LENGTH, GL_PACK_ROW_LENGTH))
            addAll(
                switches(
                    GL_DEPTH_TEST,
                    GL_CULL_FACE,
                    GL_POLYGON_OFFSET_FILL,
                    GL_PROGRAM_POINT_SIZE,
                    GL_DITHER,
                    GL_MULTISAMPLE,
                )
            )
            if (compatibility) {
                addAll(
                    switches(GL_POINT_SMOOTH, GL_LINE_SMOOTH, GL_POLYGON_SMOOTH, GL_POLYGON_STIPPLE, GL_COLOR_LOGIC_OP)
                )
                addAll(switches(GL_INDEX_LOGIC_OP))
                if (capabilities.GL_ARB_imaging) addAll(switches(GL_COLOR_TABLE))
            }
        }

    /** The compositor draws into draw buffer 0 with its own program, texture unit 0 and its sampler. */
    val present =
        GlStateSet(
            listOf(vertexArray, drawFramebuffer, program, viewport, TextureUnits(1, samplerObjects), depthMask) +
                listOf(polygonMode, DrawBuffer(0), blendFunctions) +
                switches(GL_DEPTH_TEST, GL_STENCIL_TEST, GL_SCISSOR_TEST, GL_CULL_FACE, GL_RASTERIZER_DISCARD) +
                switches(GL_COLOR_LOGIC_OP, GL_FRAMEBUFFER_SRGB, GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE) +
                switches(GL_SAMPLE_MASK)
        )

    val copyToTexture = GlStateSet(listOf(vertexArray, readFramebuffer, TextureUnits(1, false)))

    /** Our GPU copy into a new texture on unit 0, which Skia then wraps. */
    val importImage = GlStateSet(listOf(vertexArray, readFramebuffer, TextureUnits(1, false)) + skiaReset)

    /** Releasing images only deletes Skia's own objects, and no restored binding names them. */
    val releaseImages = GlStateSet(listOf(vertexArray) + skiaReset)

    /** Skia's drawing, context creation and teardown, plus our surface setup and upload preparation. */
    val skia =
        GlStateSet(
            buildList<GlStateSet.Item> {
                addAll(listOf(vertexArray, program, arrayBuffer, unpackBuffer, drawFramebuffer, readFramebuffer))
                addAll(listOf(renderbuffer, viewport, scissorBox, TextureUnits(skiaUnits, samplerObjects)))
                addAll(listOf(stencil(GL_FRONT), stencil(GL_BACK), blendFunctions, blendColor, polygonMode))
                addAll(listOf(clearColor, clearStencil))
                for (index in 0 until drawBuffers) add(DrawBuffer(index))
                addAll(switches(GL_STENCIL_TEST, GL_SCISSOR_TEST, GL_FRAMEBUFFER_SRGB))
                addAll(
                    pixelStores(GL_UNPACK_ALIGNMENT, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS, GL_UNPACK_SWAP_BYTES)
                )
                addAll(skiaReset)
                if (capabilities.GL_NV_conservative_raster) addAll(switches(GL_CONSERVATIVE_RASTERIZATION_NV))
                if (windowRectangles > 0) add(WindowRectangles(windowRectangles))
            }
        )
}

/**
 * Skia sets alignment and row length for its uploads but trusts the rest; Minecraft's atlas uploads leave skips set.
 */
internal fun prepareUploads() {
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
    glPixelStorei(GL_UNPACK_SKIP_ROWS, 0)
    glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE)
}

/** Reading RGBA bytes into client memory depends only on these. */
internal fun prepareReadback() {
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
    glPixelStorei(GL_PACK_ALIGNMENT, 4)
    glPixelStorei(GL_PACK_ROW_LENGTH, 0)
    glPixelStorei(GL_PACK_SKIP_PIXELS, 0)
    glPixelStorei(GL_PACK_SKIP_ROWS, 0)
}

/** An integer state, or a boolean one read as 0 or 1. */
private class Value(private val name: Int, private val set: (Int) -> Unit) : GlStateSet.Item(1) {
    override fun read(values: IntArray, at: Int) {
        values[at] = glGetInteger(name)
    }

    override fun write(values: IntArray, at: Int) = set(values[at])
}

/** Several integer states written back together. */
private class Values(private val names: IntArray, private val set: (IntArray) -> Unit) : GlStateSet.Item(names.size) {
    override fun read(values: IntArray, at: Int) {
        for (index in names.indices) values[at + index] = glGetInteger(names[index])
    }

    override fun write(values: IntArray, at: Int) = set(values.copyOfRange(at, at + size))
}

private class Switch(private val capability: Int) : GlStateSet.Item(1) {
    override fun read(values: IntArray, at: Int) {
        values[at] = if (glIsEnabled(capability)) 1 else 0
    }

    override fun write(values: IntArray, at: Int) = if (values[at] != 0) glEnable(capability) else glDisable(capability)
}

/** Reads through a four-value buffer: drivers return two polygon modes even though core GL sets both faces at once. */
private class Ints(private val name: Int, size: Int, private val set: (IntArray) -> Unit) : GlStateSet.Item(size) {
    override fun read(values: IntArray, at: Int) {
        val read = IntArray(4)
        glGetIntegerv(name, read)
        read.copyInto(values, at, 0, size)
    }

    override fun write(values: IntArray, at: Int) = set(values.copyOfRange(at, at + size))
}

private class Floats(private val name: Int, size: Int, private val set: (FloatArray) -> Unit) : GlStateSet.Item(size) {
    override fun read(values: IntArray, at: Int) {
        val read = FloatArray(4)
        glGetFloatv(name, read)
        for (index in 0 until size) values[at + index] = read[index].toRawBits()
    }

    override fun write(values: IntArray, at: Int) = set(FloatArray(size) { Float.fromBits(values[at + it]) })
}

/** The active unit, then the 2D texture (and sampler) of each unit from zero. */
private class TextureUnits(private val count: Int, private val samplers: Boolean) :
    GlStateSet.Item(1 + count * if (samplers) 2 else 1) {
    override fun read(values: IntArray, at: Int) {
        val active = glGetInteger(GL_ACTIVE_TEXTURE)
        values[at] = active
        var index = at + 1
        for (unit in 0 until count) {
            glActiveTexture(GL_TEXTURE0 + unit)
            values[index++] = glGetInteger(GL_TEXTURE_BINDING_2D)
            if (samplers) values[index++] = glGetInteger(GL_SAMPLER_BINDING)
        }
        glActiveTexture(active)
    }

    override fun write(values: IntArray, at: Int) {
        var index = at + 1
        for (unit in 0 until count) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, values[index++])
            if (samplers) glBindSampler(unit, values[index++])
        }
        glActiveTexture(values[at])
    }
}

/** Blend enable and color mask of one draw buffer. RenderPearl caches both per buffer, so they stay distinct. */
private class DrawBuffer(private val index: Int) : GlStateSet.Item(5) {
    override fun read(values: IntArray, at: Int) {
        values[at] = if (glIsEnabledi(GL_BLEND, index)) 1 else 0
        val mask = IntArray(4)
        glGetIntegeri_v(GL_COLOR_WRITEMASK, index, mask)
        mask.copyInto(values, at + 1)
    }

    override fun write(values: IntArray, at: Int) {
        if (values[at] != 0) glEnablei(GL_BLEND, index) else glDisablei(GL_BLEND, index)
        glColorMaski(index, values[at + 1] != 0, values[at + 2] != 0, values[at + 3] != 0, values[at + 4] != 0)
    }
}

/** Mode, count and boxes; Skia leaves exclusive rectangles behind when its last draw sat under a difference clip. */
private class WindowRectangles(max: Int) : GlStateSet.Item(2 + 4 * max) {
    override fun read(values: IntArray, at: Int) {
        values[at] = glGetInteger(GL_WINDOW_RECTANGLE_MODE_EXT)
        val count = glGetInteger(GL_NUM_WINDOW_RECTANGLES_EXT)
        values[at + 1] = count
        val box = IntArray(4)
        for (index in 0 until count) {
            glGetIntegeri_v(GL_WINDOW_RECTANGLE_EXT, index, box)
            box.copyInto(values, at + 2 + index * 4)
        }
    }

    override fun write(values: IntArray, at: Int) {
        val count = values[at + 1]
        val boxes: IntArray? = if (count == 0) null else values.copyOfRange(at + 2, at + 2 + count * 4)
        glWindowRectanglesEXT(values[at], boxes)
    }
}

private val vertexArray = Value(GL_VERTEX_ARRAY_BINDING, ::glBindVertexArray)
private val program = Value(GL_CURRENT_PROGRAM, ::glUseProgram)
private val arrayBuffer = Value(GL_ARRAY_BUFFER_BINDING) { glBindBuffer(GL_ARRAY_BUFFER, it) }
private val unpackBuffer = Value(GL_PIXEL_UNPACK_BUFFER_BINDING) { glBindBuffer(GL_PIXEL_UNPACK_BUFFER, it) }
private val packBuffer = Value(GL_PIXEL_PACK_BUFFER_BINDING) { glBindBuffer(GL_PIXEL_PACK_BUFFER, it) }
private val drawFramebuffer = Value(GL_DRAW_FRAMEBUFFER_BINDING) { glBindFramebuffer(GL_DRAW_FRAMEBUFFER, it) }
private val readFramebuffer = Value(GL_READ_FRAMEBUFFER_BINDING) { glBindFramebuffer(GL_READ_FRAMEBUFFER, it) }
private val renderbuffer = Value(GL_RENDERBUFFER_BINDING) { glBindRenderbuffer(GL_RENDERBUFFER, it) }
private val viewport = Ints(GL_VIEWPORT, 4) { glViewport(it[0], it[1], it[2], it[3]) }
private val scissorBox = Ints(GL_SCISSOR_BOX, 4) { glScissor(it[0], it[1], it[2], it[3]) }
private val polygonMode = Ints(GL_POLYGON_MODE, 1) { glPolygonMode(GL_FRONT_AND_BACK, it[0]) }
private val depthMask = Value(GL_DEPTH_WRITEMASK) { glDepthMask(it != 0) }
private val frontFace = Value(GL_FRONT_FACE, ::glFrontFace)
private val lineWidth = Floats(GL_LINE_WIDTH, 1) { glLineWidth(it[0]) }
private val blendColor = Floats(GL_BLEND_COLOR_EXT, 4) { glBlendColor(it[0], it[1], it[2], it[3]) }
private val clearColor = Floats(GL_COLOR_CLEAR_VALUE, 4) { glClearColor(it[0], it[1], it[2], it[3]) }
private val clearStencil = Value(GL_STENCIL_CLEAR_VALUE, ::glClearStencil)
private val blendFunctions =
    Values(
        intArrayOf(
            GL_BLEND_SRC_RGB,
            GL_BLEND_DST_RGB,
            GL_BLEND_SRC_ALPHA,
            GL_BLEND_DST_ALPHA,
            GL_BLEND_EQUATION_RGB,
            GL_BLEND_EQUATION_ALPHA,
        )
    ) {
        glBlendFuncSeparate(it[0], it[1], it[2], it[3])
        glBlendEquationSeparate(it[4], it[5])
    }

private fun stencil(face: Int) =
    Values(
        if (face == GL_FRONT)
            intArrayOf(
                GL_STENCIL_FUNC,
                GL_STENCIL_REF,
                GL_STENCIL_VALUE_MASK,
                GL_STENCIL_WRITEMASK,
                GL_STENCIL_FAIL,
                GL_STENCIL_PASS_DEPTH_FAIL,
                GL_STENCIL_PASS_DEPTH_PASS,
            )
        else
            intArrayOf(
                GL_STENCIL_BACK_FUNC,
                GL_STENCIL_BACK_REF,
                GL_STENCIL_BACK_VALUE_MASK,
                GL_STENCIL_BACK_WRITEMASK,
                GL_STENCIL_BACK_FAIL,
                GL_STENCIL_BACK_PASS_DEPTH_FAIL,
                GL_STENCIL_BACK_PASS_DEPTH_PASS,
            )
    ) {
        glStencilFuncSeparate(face, it[0], it[1], it[2])
        glStencilMaskSeparate(face, it[3])
        glStencilOpSeparate(face, it[4], it[5], it[6])
    }

private fun pixelStores(vararg names: Int) = names.map { name -> Value(name) { glPixelStorei(name, it) } }

private fun switches(vararg capabilities: Int) = capabilities.map(::Switch)
