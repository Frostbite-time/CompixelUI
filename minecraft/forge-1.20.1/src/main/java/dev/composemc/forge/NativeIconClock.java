package dev.composemc.forge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TickEvent;

/** Advances after TextureManager.tick, using the same condition as Minecraft 1.20.1: a loaded level, paused or not. */
@EventBusSubscriber(modid = "composemc", value = Dist.CLIENT)
public final class NativeIconClock {
    private static long tick;
    static long tick() { return tick; }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void afterTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getInstance().level != null) tick++;
    }
}
