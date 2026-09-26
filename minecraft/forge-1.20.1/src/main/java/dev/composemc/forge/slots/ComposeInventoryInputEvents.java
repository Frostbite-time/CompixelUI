package dev.composemc.forge.slots;

import dev.composemc.forge.ComposeInventoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ScreenEvent;

/** Compose text fields own their keyboard input before recipe-viewer/container shortcuts run. */
@EventBusSubscriber(modid = "composemc", value = Dist.CLIENT)
public final class ComposeInventoryInputEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof ComposeInventoryScreen<?> screen && screen.getHasTextInputFocus())
            event.setCanceled(screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers()));
    }
}
