package dev.compixel.forge.item;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Advances after TextureManager.tick, using the same running-level condition as Minecraft. */
@EventBusSubscriber(modid = "compixel", value = Dist.CLIENT)
public final class NativeIconClock {
    private static long tick;

    static long tick() {
        return tick;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void afterTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.tickRateManager().runsNormally()) tick++;
    }
}
