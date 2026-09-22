package dev.composemc.forge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;

/** Advances after TextureManager.tick, using the same running-level condition as Minecraft. */
@EventBusSubscriber(modid = "composemc", value = Dist.CLIENT)
public final class NativeIconClock {
    private static long tick;
    static long tick() { return tick; }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void afterTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !Minecraft.getInstance().isPaused()) tick++;
    }
}
