package dev.composemc.neoforge.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.composemc.neoforge.NativeGuiTargetScope;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget composemc$offscreenTarget(Minecraft minecraft) {
        return NativeGuiTargetScope.targetFor(minecraft);
    }
}
