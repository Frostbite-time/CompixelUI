package dev.compixel.forge.render

import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc

// Skia stores premultiplied RGBA. The ordinary GUI blend multiplies RGB by alpha
// again, darkening translucent controls and text edges.
private val composePipeline by lazy {
    RenderPipelines.GUI_TEXTURED.toBuilder()
        .withLocation(Identifier.fromNamespaceAndPath("compixel", "gui_premultiplied"))
        .withColorTargetState(ColorTargetState(BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
        .build()
}

internal fun presentPremultiplied(
    destination: ScreenRenderDestination,
    view: GpuTextureView,
    sampler: GpuSampler,
    flipY: Boolean,
) {
    presentFrame(destination, composePipeline, view, sampler, flipY)
}

internal fun presentFrame(
    destination: ScreenRenderDestination,
    pipeline: RenderPipeline,
    view: GpuTextureView,
    sampler: GpuSampler,
    flipY: Boolean,
) {
    val graphics = destination.graphics
    graphics.submitGuiElementRenderState(
        frameBlitRenderState(
            destination.metrics,
            graphics.pose(),
            pipeline,
            TextureSetup.singleTexture(view, sampler),
            graphics.peekScissorStack(),
            flipY,
        )
    )
}

internal fun frameBlitRenderState(
    metrics: ScreenMetrics,
    pose: Matrix3x2fc,
    pipeline: RenderPipeline,
    texture: TextureSetup,
    scissor: ScreenRectangle?,
    flipY: Boolean,
): BlitRenderState =
    BlitRenderState(
        pipeline,
        texture,
        // Minecraft projects GUI units at exactly guiScale pixels, not framebufferWidth / guiWidth.
        // Submit pixel dimensions and cancel that scale so rounded GUI sizes cannot stretch the frame.
        Matrix3x2f(pose).scale(metrics.renderCoordinate(1f)),
        0,
        0,
        metrics.framebufferWidth,
        metrics.framebufferHeight,
        0f,
        1f,
        if (flipY) 1f else 0f,
        if (flipY) 0f else 1f,
        -1,
        scissor,
    )
