package dev.compixel.forge.item

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import dev.compixel.forge.render.ScreenFrameRenderer
import dev.compixel.render.CpuDetail
import dev.compixel.render.GpuPhase
import dev.compixel.render.gl.OpenGlDestination
import dev.compixel.render.measureDetail
import kotlin.math.ceil
import kotlin.math.floor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.jetbrains.skia.IRect
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL33C.*

/** An owned preparation target for native GUI content, used only on the render thread. */
internal class NativeGuiRenderTarget(private val backend: ScreenFrameRenderer) : AutoCloseable {
    private var target: TextureTarget? = null

    /** The pixels of the last draw, for copies. */
    var destination: OpenGlDestination? = null
        private set

    /**
     * Draws [content] over the GUI area mapped onto [width] x [height] pixels. Without [cells], the whole target is
     * cleared first; with them, only those pixel rectangles (top-left origin) are, and the rest keeps its pixels. A
     * target created by this call starts transparent either way.
     */
    fun <T> draw(
        width: Int,
        height: Int,
        guiWidth: Float,
        guiHeight: Float,
        phase: GpuPhase = GpuPhase.ITEMS,
        cells: List<IRect>? = null,
        content: (GuiGraphics) -> T,
    ): Pair<OpenGlDestination, T> {
        RenderSystem.assertOnRenderThread()
        val captureStart = if (backend.profiler != null) System.nanoTime() else 0
        val minecraft = Minecraft.getInstance()
        val drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it) }
        val projection = Matrix4f(RenderSystem.getProjectionMatrix())
        val sorting = RenderSystem.getVertexSorting()
        val textureMatrix = Matrix4f(RenderSystem.getTextureMatrix())
        val shaderColor = RenderSystem.getShaderColor().copyOf()
        val clearColor = FloatArray(4).also { glGetFloatv(GL_COLOR_CLEAR_VALUE, it) }
        val clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE)
        val shader = RenderSystem.getShader()
        val shaderTextures = IntArray(12) { RenderSystem.getShaderTexture(it) }
        val depth = glIsEnabled(GL_DEPTH_TEST)
        val depthMask = glGetBoolean(GL_DEPTH_WRITEMASK)
        val blend = glIsEnabled(GL_BLEND)
        val blendFunctions =
            intArrayOf(
                glGetInteger(GL_BLEND_SRC_RGB),
                glGetInteger(GL_BLEND_DST_RGB),
                glGetInteger(GL_BLEND_SRC_ALPHA),
                glGetInteger(GL_BLEND_DST_ALPHA),
            )
        val colorMask = IntArray(4).also { glGetIntegerv(GL_COLOR_WRITEMASK, it) }
        val scissor = glIsEnabled(GL_SCISSOR_TEST)
        val scissorBox = IntArray(4).also { glGetIntegerv(GL_SCISSOR_BOX, it) }
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix()
        backend.profiler?.addDetail(CpuDetail.GL_CAPTURE, System.nanoTime() - captureStart)
        try {
            return backend.nativeGpu(phase) {
                backend.profiler.measureDetail(CpuDetail.NATIVE_DRAW) {
                    RenderSystem.disableScissor()
                    RenderSystem.colorMask(true, true, true, true)
                    RenderSystem.depthMask(true)
                    val created = target?.width != width || target?.height != height
                    if (created) {
                        target?.destroyBuffers()
                        target = null
                        destination = null
                        target = TextureTarget(width, height, true, Minecraft.ON_OSX)
                    }
                    val output = checkNotNull(target)
                    output.setClearColor(0f, 0f, 0f, 0f)
                    if (created || cells == null) output.clear(Minecraft.ON_OSX)
                    else {
                        // A scissored clear leaves the other cells; GL scissor boxes start at the bottom-left corner.
                        cells.forEach { cell ->
                            RenderSystem.enableScissor(cell.left, height - cell.bottom, cell.width, cell.height)
                            output.clear(Minecraft.ON_OSX)
                        }
                        RenderSystem.disableScissor()
                    }
                    output.bindWrite(true)
                    RenderSystem.setProjectionMatrix(
                        Matrix4f().setOrtho(0f, guiWidth, guiHeight, 0f, 1000f, 21000f),
                        VertexSorting.ORTHOGRAPHIC_Z,
                    )
                    modelView.identity().translate(0f, 0f, -11000f)
                    RenderSystem.applyModelViewMatrix()
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                    Lighting.setupFor3DItems()
                    // GuiGraphics normally scissors against the window. This viewport is an offscreen target.
                    val graphics =
                        object : GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource()) {
                            override fun guiWidth() = ceil(guiWidth).toInt()

                            override fun guiHeight() = ceil(guiHeight).toInt()

                            private val clips = ArrayDeque<ScreenRectangle>()

                            override fun enableScissor(minX: Int, minY: Int, maxX: Int, maxY: Int) {
                                val matrix = pose().last().pose()
                                val corners =
                                    arrayOf(
                                        Vector3f(minX.toFloat(), minY.toFloat(), 0f),
                                        Vector3f(maxX.toFloat(), minY.toFloat(), 0f),
                                        Vector3f(minX.toFloat(), maxY.toFloat(), 0f),
                                        Vector3f(maxX.toFloat(), maxY.toFloat(), 0f),
                                    )
                                corners.forEach { matrix.transformPosition(it) }
                                val left = floor(corners.minOf { it.x }).toInt()
                                val top = floor(corners.minOf { it.y }).toInt()
                                val right = ceil(corners.maxOf { it.x }).toInt()
                                val bottom = ceil(corners.maxOf { it.y }).toInt()
                                val requested = ScreenRectangle(left, top, right - left, bottom - top)
                                val parent = clips.lastOrNull()
                                clips.addLast(
                                    if (parent == null) requested
                                    else parent.intersection(requested) ?: ScreenRectangle(0, 0, 0, 0)
                                )
                                applyClip()
                            }

                            override fun disableScissor() {
                                check(clips.isNotEmpty()) { "Scissor stack underflow" }
                                clips.removeLast()
                                applyClip()
                            }

                            override fun containsPointInScissor(x: Int, y: Int): Boolean =
                                clips.lastOrNull()?.containsPoint(x, y) ?: true

                            private fun applyClip() {
                                flush()
                                val rectangle = clips.lastOrNull()
                                if (rectangle == null) RenderSystem.disableScissor()
                                else {
                                    val left = (rectangle.left() * width / guiWidth).toInt().coerceIn(0, width)
                                    val top = (rectangle.top() * height / guiHeight).toInt().coerceIn(0, height)
                                    val right = (rectangle.right() * width / guiWidth).toInt().coerceIn(left, width)
                                    val bottom = (rectangle.bottom() * height / guiHeight).toInt().coerceIn(top, height)
                                    RenderSystem.enableScissor(left, height - bottom, right - left, bottom - top)
                                }
                            }
                        }
                    val result = content(graphics)
                    graphics.flush()
                    val drawn = OpenGlDestination(output.frameBufferId, width, height)
                    destination = drawn
                    drawn to result
                }
            }
        } finally {
            val restoreStart = if (backend.profiler != null) System.nanoTime() else 0
            modelView.popMatrix()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setProjectionMatrix(projection, sorting)
            RenderSystem.setTextureMatrix(textureMatrix)
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3])
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
            RenderSystem.clearDepth(clearDepth)
            RenderSystem.setShader { shader }
            shaderTextures.forEachIndexed(RenderSystem::setShaderTexture)
            Lighting.setupFor3DItems()
            if (depth) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
            RenderSystem.depthMask(depthMask)
            if (blend) RenderSystem.enableBlend() else RenderSystem.disableBlend()
            RenderSystem.blendFuncSeparate(blendFunctions[0], blendFunctions[1], blendFunctions[2], blendFunctions[3])
            RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0)
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            else RenderSystem.disableScissor()
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
            backend.profiler?.addDetail(CpuDetail.GL_RESTORE, System.nanoTime() - restoreStart)
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        val drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        try {
            target?.destroyBuffers()
        } finally {
            target = null
            destination = null
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)
        }
    }
}
