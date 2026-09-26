package dev.composemc.forge.item

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import dev.composemc.render.gl.OpenGlDestination
import dev.composemc.render.CpuDetail
import dev.composemc.render.GpuPhase
import dev.composemc.render.measureDetail
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Matrix4f
import org.lwjgl.opengl.GL33C.*
import dev.composemc.forge.render.ScreenFrameRenderer

/** An owned preparation target for native GUI content, used only on the render thread. */
internal class NativeGuiRenderTarget(private val backend: ScreenFrameRenderer) : AutoCloseable {
    private var target: TextureTarget? = null

    fun <T> draw(width: Int, height: Int, guiWidth: Float, guiHeight: Float, phase: GpuPhase = GpuPhase.ITEMS,
                 content: (GuiGraphics) -> T): Pair<OpenGlDestination, T> {
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
        val blendFunctions = intArrayOf(glGetInteger(GL_BLEND_SRC_RGB), glGetInteger(GL_BLEND_DST_RGB),
            glGetInteger(GL_BLEND_SRC_ALPHA), glGetInteger(GL_BLEND_DST_ALPHA))
        val colorMask = IntArray(4).also { glGetIntegerv(GL_COLOR_WRITEMASK, it) }
        val scissor = glIsEnabled(GL_SCISSOR_TEST)
        val scissorBox = IntArray(4).also { glGetIntegerv(GL_SCISSOR_BOX, it) }
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix()
        backend.profiler?.addDetail(CpuDetail.GL_CAPTURE, System.nanoTime() - captureStart)
        try {
            return backend.nativeGpu(phase) { backend.profiler.measureDetail(CpuDetail.NATIVE_DRAW) {
            RenderSystem.disableScissor()
            RenderSystem.colorMask(true, true, true, true)
            RenderSystem.depthMask(true)
            if (target?.width != width || target?.height != height) {
                target?.destroyBuffers()
                target = null
                target = TextureTarget(width, height, true, Minecraft.ON_OSX)
            }
            val output = checkNotNull(target)
            output.setClearColor(0f, 0f, 0f, 0f)
            output.clear(Minecraft.ON_OSX)
            output.bindWrite(true)
            RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, guiWidth, guiHeight, 0f, 1000f, 21000f), VertexSorting.ORTHOGRAPHIC_Z)
            modelView.identity().translate(0f, 0f, -11000f)
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            Lighting.setupFor3DItems()
            val graphics = GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())
            val result = content(graphics)
            graphics.flush()
            OpenGlDestination(output.frameBufferId, width, height) to result
            } }
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
        try { target?.destroyBuffers() } finally {
            target = null
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)
        }
    }
}
