package dev.composemc.neoforge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

/** Limits offscreen GUI redirection to this adapter's synchronous capture call. */
public final class NativeGuiTargetScope {
    private static final ThreadLocal<RenderTarget> TARGET = new ThreadLocal<>();

    private NativeGuiTargetScope() {}

    public static void renderTo(RenderTarget target, Runnable draw) {
        if (TARGET.get() != null) throw new IllegalStateException("Nested native GUI capture");
        TARGET.set(target);
        try {
            draw.run();
        } finally {
            TARGET.remove();
        }
    }

    public static RenderTarget targetFor(Minecraft minecraft) {
        RenderTarget target = TARGET.get();
        return target != null ? target : minecraft.getMainRenderTarget();
    }
}
