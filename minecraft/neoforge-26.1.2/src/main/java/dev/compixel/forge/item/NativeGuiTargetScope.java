package dev.compixel.forge.item;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;

/** Selects the offscreen target only for this adapter's active capture renderer. */
public final class NativeGuiTargetScope {
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();

    private record Capture(GuiRenderer renderer, RenderTarget target) {}

    private NativeGuiTargetScope() {}

    public static void renderTo(GuiRenderer renderer, RenderTarget target, Runnable draw) {
        if (CAPTURE.get() != null) throw new IllegalStateException("Nested native GUI capture");
        CAPTURE.set(new Capture(renderer, target));
        try {
            draw.run();
        } finally {
            CAPTURE.remove();
        }
    }

    public static RenderTarget targetFor(GuiRenderer renderer, RenderTarget original) {
        Capture capture = CAPTURE.get();
        return capture != null && capture.renderer() == renderer ? capture.target() : original;
    }
}
