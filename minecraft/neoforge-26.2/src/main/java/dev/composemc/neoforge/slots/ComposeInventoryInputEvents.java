package dev.composemc.neoforge.slots;

import dev.composemc.neoforge.NeoForgeComposeInventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Compose text fields own their keyboard input before recipe-viewer/container shortcuts run. */
@EventBusSubscriber(modid = "composemc", value = Dist.CLIENT)
public final class ComposeInventoryInputEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof NeoForgeComposeInventoryScreen<?> screen && screen.getHasTextInputFocus())
            event.setCanceled(screen.keyPressed(new net.minecraft.client.input.KeyEvent(event.getKeyCode(), event.getScanCode(), event.getModifiers())));
    }
}
