package dev.compixel.forge;

import dev.compixel.forge.sync.MenuSyncNetworking;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/** Server-safe entry point. No Kotlin, Compose, AWT or Skiko initialization. */
@Mod("compixel")
public final class CompixelCommon {
    public CompixelCommon() {
        var bus =
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        MenuSyncNetworking.register();
        MinecraftForge.EVENT_BUS.addListener(MenuSyncNetworking::opened);
        MinecraftForge.EVENT_BUS.addListener(MenuSyncNetworking::tick);
        MinecraftForge.EVENT_BUS.addListener(MenuSyncNetworking::stopped);
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> {
            dev.compixel.platform.ComposeRuntime.requireAvailable();
            dev.compixel.platform.KotlinRuntime.requireAvailable();
            ClientBootstrap.INSTANCE.register(bus);
        });
    }
}
