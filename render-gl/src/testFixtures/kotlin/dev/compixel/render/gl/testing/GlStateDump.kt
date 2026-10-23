package dev.compixel.render.gl.testing

import dev.compixel.render.gl.GlStateCheck
import org.lwjgl.opengl.EXTWindowRectangles.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL45C.*
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.NVConservativeRaster.GL_CONSERVATIVE_RASTERIZATION_NV

/**
 * The GL state a host can observe, read independently of what the renderer saves, so comparing it across a scope finds
 * anything the scope leaked. Every query depends on the context's capabilities, so reading raises no GL error.
 */
internal class GlStateDump private constructor(private val values: Map<String, String>) {
    fun assertUnchanged(after: String) {
        val now = Reader(GL.getCapabilities()).read()
        val changed = values.keys.filter { now[it] != values[it] }
        check(changed.isEmpty()) {
            "OpenGL state changed by $after: " + changed.joinToString { "$it ${values[it]} -> ${now[it]}" }
        }
    }

    companion object : GlStateCheck {
        fun capture() = GlStateDump(Reader(GL.getCapabilities()).read())

        override fun before(): () -> Unit {
            val dump = capture()
            return { dump.assertUnchanged("an isolated renderer scope") }
        }
    }
}

private class Reader(private val capabilities: GLCapabilities) {
    private val values = linkedMapOf<String, String>()
    private val drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS)

    fun read(): Map<String, String> {
        bindings()
        vertexAttributes()
        for (buffer in 0 until drawBuffers) integer(
            "bound draw framebuffer DRAW_BUFFER$buffer",
            GL_DRAW_BUFFER0 + buffer,
        )
        integer("bound read framebuffer READ_BUFFER", GL_READ_BUFFER)
        viewports()
        rasterization()
        textures()
        fragments()
        for (name in pixelStore) integer("pixel store 0x${name.toString(16)}", name)
        for (hint in hints) integer("hint 0x${hint.toString(16)}", hint)
        if (capabilities.OpenGL40 || capabilities.GL_ARB_tessellation_shader) {
            integer("PATCH_VERTICES", GL_PATCH_VERTICES)
            floats("PATCH_DEFAULT_OUTER_LEVEL", GL_PATCH_DEFAULT_OUTER_LEVEL, 4)
            floats("PATCH_DEFAULT_INNER_LEVEL", GL_PATCH_DEFAULT_INNER_LEVEL, 2)
        }
        for (target in queries()) values["current query 0x${target.toString(16)}"] =
            "${glGetQueryi(target, GL_CURRENT_QUERY)}"
        if (capabilities.GL_EXT_window_rectangles) {
            integer("WINDOW_RECTANGLE_MODE_EXT", GL_WINDOW_RECTANGLE_MODE_EXT)
            val count = glGetInteger(GL_NUM_WINDOW_RECTANGLES_EXT)
            values["NUM_WINDOW_RECTANGLES_EXT"] = "$count"
            for (index in 0 until count) indexedInts("window rectangle $index", GL_WINDOW_RECTANGLE_EXT, index)
        }
        return values
    }

    private fun bindings() {
        integer("CURRENT_PROGRAM", GL_CURRENT_PROGRAM)
        integer("VERTEX_ARRAY_BINDING", GL_VERTEX_ARRAY_BINDING)
        integer("ARRAY_BUFFER_BINDING", GL_ARRAY_BUFFER_BINDING)
        integer("DRAW_FRAMEBUFFER_BINDING", GL_DRAW_FRAMEBUFFER_BINDING)
        integer("READ_FRAMEBUFFER_BINDING", GL_READ_FRAMEBUFFER_BINDING)
        integer("RENDERBUFFER_BINDING", GL_RENDERBUFFER_BINDING)
        integer("PIXEL_PACK_BUFFER_BINDING", GL_PIXEL_PACK_BUFFER_BINDING)
        integer("PIXEL_UNPACK_BUFFER_BINDING", GL_PIXEL_UNPACK_BUFFER_BINDING)
        integer("COPY_READ_BUFFER_BINDING", GL_COPY_READ_BUFFER)
        integer("COPY_WRITE_BUFFER_BINDING", GL_COPY_WRITE_BUFFER)
        integer("TEXTURE_BUFFER_BINDING", GL_TEXTURE_BUFFER)
        integer("UNIFORM_BUFFER_BINDING", GL_UNIFORM_BUFFER_BINDING)
        integer("TRANSFORM_FEEDBACK_BUFFER_BINDING", GL_TRANSFORM_FEEDBACK_BUFFER_BINDING)
        for (index in 0 until glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS)) values["UNIFORM_BUFFER_BINDING $index"] =
            "${glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, index)}"
        if (capabilities.OpenGL40 || capabilities.GL_ARB_draw_indirect)
            integer("DRAW_INDIRECT_BUFFER_BINDING", GL_DRAW_INDIRECT_BUFFER_BINDING)
        if (capabilities.OpenGL40 || capabilities.GL_ARB_transform_feedback2)
            integer("TRANSFORM_FEEDBACK_BINDING", GL_TRANSFORM_FEEDBACK_BINDING)
    }

    /** The bound vertex array's own state, and the context's current generic attributes past the first. */
    private fun vertexAttributes() {
        val attributes = glGetInteger(GL_MAX_VERTEX_ATTRIBS)
        if (glGetInteger(GL_VERTEX_ARRAY_BINDING) != 0) {
            integer("vertex array ELEMENT_ARRAY_BUFFER_BINDING", GL_ELEMENT_ARRAY_BUFFER_BINDING)
            val divisor = capabilities.OpenGL33 || capabilities.GL_ARB_instanced_arrays
            for (attribute in 0 until attributes) {
                val state = attributeState.map { glGetVertexAttribi(attribute, it) }.toMutableList()
                if (divisor) state += glGetVertexAttribi(attribute, GL_VERTEX_ATTRIB_ARRAY_DIVISOR)
                values["vertex array attribute $attribute"] =
                    "$state @ ${glGetVertexAttribPointer(attribute, GL_VERTEX_ATTRIB_ARRAY_POINTER)}"
            }
        }
        for (attribute in 1 until attributes) {
            val current = FloatArray(4)
            glGetVertexAttribfv(attribute, GL_CURRENT_VERTEX_ATTRIB, current)
            values["current attribute $attribute"] = current.toList().toString()
        }
    }

    private fun viewports() {
        if (capabilities.OpenGL41 || capabilities.GL_ARB_viewport_array) {
            for (index in 0 until glGetInteger(GL_MAX_VIEWPORTS)) {
                val viewport = FloatArray(4)
                val depth = DoubleArray(2)
                glGetFloati_v(GL_VIEWPORT, index, viewport)
                glGetDoublei_v(GL_DEPTH_RANGE, index, depth)
                values["viewport $index"] = viewport.toList().toString()
                values["depth range $index"] = depth.toList().toString()
                indexedInts("scissor box $index", GL_SCISSOR_BOX, index)
                values["enabled SCISSOR_TEST $index"] = "${glIsEnabledi(GL_SCISSOR_TEST, index)}"
            }
        } else {
            ints("viewport", GL_VIEWPORT, 4)
            ints("scissor box", GL_SCISSOR_BOX, 4)
            val depth = DoubleArray(2)
            glGetDoublev(GL_DEPTH_RANGE, depth)
            values["depth range"] = depth.toList().toString()
            switch("SCISSOR_TEST", GL_SCISSOR_TEST)
        }
        if (capabilities.OpenGL45 || capabilities.GL_ARB_clip_control) {
            integer("CLIP_ORIGIN", GL_CLIP_ORIGIN)
            integer("CLIP_DEPTH_MODE", GL_CLIP_DEPTH_MODE)
        }
        for (distance in 0 until glGetInteger(GL_MAX_CLIP_DISTANCES)) switch(
            "CLIP_DISTANCE$distance",
            GL_CLIP_DISTANCE0 + distance,
        )
        switch("DEPTH_CLAMP", GL_DEPTH_CLAMP)
        integer("PROVOKING_VERTEX", GL_PROVOKING_VERTEX)
        integer("CLAMP_READ_COLOR", GL_CLAMP_READ_COLOR)
    }

    private fun rasterization() {
        switch("RASTERIZER_DISCARD", GL_RASTERIZER_DISCARD)
        floats("POINT_SIZE", GL_POINT_SIZE, 1)
        floats("POINT_FADE_THRESHOLD_SIZE", GL_POINT_FADE_THRESHOLD_SIZE, 1)
        integer("POINT_SPRITE_COORD_ORIGIN", GL_POINT_SPRITE_COORD_ORIGIN)
        switch("PROGRAM_POINT_SIZE", GL_PROGRAM_POINT_SIZE)
        floats("LINE_WIDTH", GL_LINE_WIDTH, 1)
        switch("LINE_SMOOTH", GL_LINE_SMOOTH)
        switch("CULL_FACE", GL_CULL_FACE)
        integer("CULL_FACE_MODE", GL_CULL_FACE_MODE)
        integer("FRONT_FACE", GL_FRONT_FACE)
        switch("POLYGON_SMOOTH", GL_POLYGON_SMOOTH)
        ints("POLYGON_MODE", GL_POLYGON_MODE, 1)
        floats("POLYGON_OFFSET_FACTOR", GL_POLYGON_OFFSET_FACTOR, 1)
        floats("POLYGON_OFFSET_UNITS", GL_POLYGON_OFFSET_UNITS, 1)
        switch("POLYGON_OFFSET_POINT", GL_POLYGON_OFFSET_POINT)
        switch("POLYGON_OFFSET_LINE", GL_POLYGON_OFFSET_LINE)
        switch("POLYGON_OFFSET_FILL", GL_POLYGON_OFFSET_FILL)
        if (capabilities.GL_NV_conservative_raster)
            switch("CONSERVATIVE_RASTERIZATION_NV", GL_CONSERVATIVE_RASTERIZATION_NV)
        switch("MULTISAMPLE", GL_MULTISAMPLE)
        switch("SAMPLE_ALPHA_TO_COVERAGE", GL_SAMPLE_ALPHA_TO_COVERAGE)
        switch("SAMPLE_ALPHA_TO_ONE", GL_SAMPLE_ALPHA_TO_ONE)
        switch("SAMPLE_COVERAGE", GL_SAMPLE_COVERAGE)
        floats("SAMPLE_COVERAGE_VALUE", GL_SAMPLE_COVERAGE_VALUE, 1)
        integer("SAMPLE_COVERAGE_INVERT", GL_SAMPLE_COVERAGE_INVERT)
        switch("SAMPLE_MASK", GL_SAMPLE_MASK)
        for (word in 0 until glGetInteger(GL_MAX_SAMPLE_MASK_WORDS)) values["SAMPLE_MASK_VALUE $word"] =
            "${glGetIntegeri(GL_SAMPLE_MASK_VALUE, word)}"
        if (capabilities.OpenGL40) {
            switch("SAMPLE_SHADING", GL_SAMPLE_SHADING)
            floats("MIN_SAMPLE_SHADING_VALUE", GL_MIN_SAMPLE_SHADING_VALUE, 1)
        }
    }

    private fun textures() {
        val bindings =
            if (capabilities.OpenGL40 || capabilities.GL_ARB_texture_cube_map_array)
                textureBindings + GL_TEXTURE_BINDING_CUBE_MAP_ARRAY
            else textureBindings
        val samplers = capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects
        val active = glGetInteger(GL_ACTIVE_TEXTURE)
        values["ACTIVE_TEXTURE"] = "${active - GL_TEXTURE0}"
        for (unit in 0 until glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)) {
            glActiveTexture(GL_TEXTURE0 + unit)
            val bound = bindings.map(::glGetInteger)
            values["texture unit $unit"] =
                if (samplers) "$bound sampler ${glGetInteger(GL_SAMPLER_BINDING)}" else "$bound"
        }
        glActiveTexture(active)
        switch("TEXTURE_CUBE_MAP_SEAMLESS", GL_TEXTURE_CUBE_MAP_SEAMLESS)
    }

    private fun fragments() {
        for (name in stencil) integer("stencil 0x${name.toString(16)}", name)
        switch("STENCIL_TEST", GL_STENCIL_TEST)
        switch("DEPTH_TEST", GL_DEPTH_TEST)
        integer("DEPTH_FUNC", GL_DEPTH_FUNC)
        integer("DEPTH_WRITEMASK", GL_DEPTH_WRITEMASK)
        for (buffer in 0 until drawBuffers) {
            values["draw buffer $buffer BLEND"] = "${glIsEnabledi(GL_BLEND, buffer)}"
            indexedInts("draw buffer $buffer COLOR_WRITEMASK", GL_COLOR_WRITEMASK, buffer)
            if (capabilities.OpenGL40)
                values["draw buffer $buffer blend functions"] = blend.map { glGetIntegeri(it, buffer) }.toString()
        }
        values["blend functions"] = blend.map(::glGetInteger).toString()
        floats("BLEND_COLOR", GL_BLEND_COLOR, 4)
        switch("FRAMEBUFFER_SRGB", GL_FRAMEBUFFER_SRGB)
        switch("DITHER", GL_DITHER)
        switch("COLOR_LOGIC_OP", GL_COLOR_LOGIC_OP)
        integer("LOGIC_OP_MODE", GL_LOGIC_OP_MODE)
        ints("COLOR_WRITEMASK", GL_COLOR_WRITEMASK, 4)
        floats("COLOR_CLEAR_VALUE", GL_COLOR_CLEAR_VALUE, 4)
        values["DEPTH_CLEAR_VALUE"] = "${glGetDouble(GL_DEPTH_CLEAR_VALUE)}"
        integer("STENCIL_CLEAR_VALUE", GL_STENCIL_CLEAR_VALUE)
        switch("PRIMITIVE_RESTART", GL_PRIMITIVE_RESTART)
        if (capabilities.OpenGL43 || capabilities.GL_ARB_ES3_compatibility)
            switch("PRIMITIVE_RESTART_FIXED_INDEX", GL_PRIMITIVE_RESTART_FIXED_INDEX)
        integer("PRIMITIVE_RESTART_INDEX", GL_PRIMITIVE_RESTART_INDEX)
    }

    private fun queries() = buildList {
        addAll(listOf(GL_SAMPLES_PASSED, GL_PRIMITIVES_GENERATED, GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN))
        if (capabilities.OpenGL33 || capabilities.GL_ARB_occlusion_query2) add(GL_ANY_SAMPLES_PASSED)
        if (capabilities.OpenGL33 || capabilities.GL_ARB_timer_query) add(GL_TIME_ELAPSED)
    }

    private fun integer(name: String, pname: Int) {
        values[name] = "${glGetInteger(pname)}"
    }

    private fun switch(name: String, capability: Int) {
        values["enabled $name"] = "${glIsEnabled(capability)}"
    }

    private fun ints(name: String, pname: Int, count: Int) {
        val read = IntArray(4)
        glGetIntegerv(pname, read)
        values[name] = read.take(count).toString()
    }

    private fun indexedInts(name: String, pname: Int, index: Int) {
        val read = IntArray(4)
        glGetIntegeri_v(pname, index, read)
        values[name] = read.toList().toString()
    }

    private fun floats(name: String, pname: Int, count: Int) {
        val read = FloatArray(4)
        glGetFloatv(pname, read)
        values[name] = read.take(count).toString()
    }
}

private const val GL_BLEND_COLOR = 0x8005

private val textureBindings =
    listOf(
        GL_TEXTURE_BINDING_1D,
        GL_TEXTURE_BINDING_2D,
        GL_TEXTURE_BINDING_3D,
        GL_TEXTURE_BINDING_1D_ARRAY,
        GL_TEXTURE_BINDING_2D_ARRAY,
        GL_TEXTURE_BINDING_RECTANGLE,
        GL_TEXTURE_BINDING_BUFFER,
        GL_TEXTURE_BINDING_CUBE_MAP,
        GL_TEXTURE_BINDING_2D_MULTISAMPLE,
        GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY,
    )

private val attributeState =
    listOf(
        GL_VERTEX_ATTRIB_ARRAY_ENABLED,
        GL_VERTEX_ATTRIB_ARRAY_SIZE,
        GL_VERTEX_ATTRIB_ARRAY_STRIDE,
        GL_VERTEX_ATTRIB_ARRAY_TYPE,
        GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,
        GL_VERTEX_ATTRIB_ARRAY_INTEGER,
        GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,
    )

private val stencil =
    listOf(
        GL_STENCIL_FUNC,
        GL_STENCIL_REF,
        GL_STENCIL_VALUE_MASK,
        GL_STENCIL_WRITEMASK,
        GL_STENCIL_FAIL,
        GL_STENCIL_PASS_DEPTH_FAIL,
        GL_STENCIL_PASS_DEPTH_PASS,
        GL_STENCIL_BACK_FUNC,
        GL_STENCIL_BACK_REF,
        GL_STENCIL_BACK_VALUE_MASK,
        GL_STENCIL_BACK_WRITEMASK,
        GL_STENCIL_BACK_FAIL,
        GL_STENCIL_BACK_PASS_DEPTH_FAIL,
        GL_STENCIL_BACK_PASS_DEPTH_PASS,
    )

private val blend =
    listOf(
        GL_BLEND_SRC_RGB,
        GL_BLEND_DST_RGB,
        GL_BLEND_SRC_ALPHA,
        GL_BLEND_DST_ALPHA,
        GL_BLEND_EQUATION_RGB,
        GL_BLEND_EQUATION_ALPHA,
    )

private val pixelStore =
    listOf(
        GL_UNPACK_SWAP_BYTES,
        GL_UNPACK_LSB_FIRST,
        GL_UNPACK_ROW_LENGTH,
        GL_UNPACK_SKIP_ROWS,
        GL_UNPACK_SKIP_PIXELS,
        GL_UNPACK_ALIGNMENT,
        GL_UNPACK_IMAGE_HEIGHT,
        GL_UNPACK_SKIP_IMAGES,
        GL_PACK_SWAP_BYTES,
        GL_PACK_LSB_FIRST,
        GL_PACK_ROW_LENGTH,
        GL_PACK_SKIP_ROWS,
        GL_PACK_SKIP_PIXELS,
        GL_PACK_ALIGNMENT,
        GL_PACK_IMAGE_HEIGHT,
        GL_PACK_SKIP_IMAGES,
    )

private val hints =
    listOf(GL_LINE_SMOOTH_HINT, GL_POLYGON_SMOOTH_HINT, GL_TEXTURE_COMPRESSION_HINT, GL_FRAGMENT_SHADER_DERIVATIVE_HINT)
