package dev.compixel.forge;

import dev.compixel.platform.ComposeRuntime;
import dev.compixel.platform.KotlinRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(value = CompixelClient.MOD_ID, dist = Dist.CLIENT)
public final class CompixelClient {
    public static final String MOD_ID = "compixel";

    public CompixelClient(IEventBus modEventBus, ModContainer container) {
        ComposeRuntime.requireAvailable();
        KotlinRuntime.requireAvailable();
        ClientBootstrap.INSTANCE.register(modEventBus, container);
    }
}
