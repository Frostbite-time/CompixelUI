package dev.composemc.neoforge;

import dev.composemc.platform.KotlinRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = ComposeMcNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class ComposeMcNeoForge {
    public static final String MOD_ID = "composemc";

    public ComposeMcNeoForge(IEventBus modEventBus) {
        KotlinRuntime.requireAvailable();
        ClientBootstrap.INSTANCE.register(modEventBus);
    }
}
