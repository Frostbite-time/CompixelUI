package dev.composemc.forge;

import dev.composemc.platform.ComposeRuntime;
import dev.composemc.platform.KotlinRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = ComposeMcClient.MOD_ID, dist = Dist.CLIENT)
public final class ComposeMcClient {
    public static final String MOD_ID = "composemc";

    public ComposeMcClient(IEventBus modEventBus) {
        ComposeRuntime.requireAvailable();
        KotlinRuntime.requireAvailable();
        ClientBootstrap.INSTANCE.register(modEventBus);
    }
}
