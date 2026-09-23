package dev.composemc.neoforge.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.composemc.neoforge.NativeGuiTargetScope;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @ModifyExpressionValue(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget composemc$offscreenTarget(RenderTarget original) {
        return NativeGuiTargetScope.targetFor((GuiRenderer) (Object) this, original);
    }
}
