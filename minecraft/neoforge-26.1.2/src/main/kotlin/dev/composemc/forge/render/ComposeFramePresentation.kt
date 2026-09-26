package dev.composemc.forge.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f

// Skia stores premultiplied RGBA. The ordinary GUI blend multiplies RGB by alpha
// again, darkening translucent controls and text edges.
private val composePipeline by lazy {
    RenderPipelines.GUI_TEXTURED.toBuilder()
        .withLocation(Identifier.fromNamespaceAndPath("composemc", "gui_premultiplied"))
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        .build()
}

internal fun presentPremultiplied(
    destination: ScreenRenderDestination, view: GpuTextureView, sampler: GpuSampler, flipY: Boolean,
) {
    val graphics = destination.graphics
    graphics.submitGuiElementRenderState(BlitRenderState(
        composePipeline, TextureSetup.singleTexture(view, sampler), Matrix3x2f(graphics.pose()),
        0, 0, destination.metrics.guiWidth, destination.metrics.guiHeight,
        0f, 1f, if (flipY) 1f else 0f, if (flipY) 0f else 1f, -1, graphics.peekScissorStack(),
    ))
}
