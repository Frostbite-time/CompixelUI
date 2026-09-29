package dev.compixel.forge.slots;

import dev.compixel.forge.ComposeInventoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** Compose text fields own their keyboard input before recipe-viewer/container shortcuts run. */
@EventBusSubscriber(modid = "compixel", value = Dist.CLIENT)
public final class ComposeInventoryInputEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof ComposeInventoryScreen<?> screen && screen.getHasTextInputFocus())
            event.setCanceled(screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers()));
    }
}
