package dev.composemc.forge;

import dev.composemc.forge.sync.MenuSyncNetworking;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;

/** Server-safe entry point. No Kotlin, Compose, AWT or Skiko initialization. */
@Mod("composemc")
public final class ComposeMcCommon {
    public ComposeMcCommon() {
        var bus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        MenuSyncNetworking.register();
        MinecraftForge.EVENT_BUS.addListener(MenuSyncNetworking::opened);
        MinecraftForge.EVENT_BUS.addListener(MenuSyncNetworking::tick);
        MinecraftForge.EVENT_BUS.addListener(MenuSyncNetworking::stopped);
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> {
                dev.composemc.platform.KotlinRuntime.requireAvailable();
                ClientBootstrap.INSTANCE.register(bus);
            });
    }
}
