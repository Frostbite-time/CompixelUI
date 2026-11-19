package dev.compixel.forge;

import dev.compixel.forge.sync.MenuSyncNetworking;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModLoadingException;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;

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
            var kotlinIssue = dev.compixel.platform.internal.KotlinRuntime.check();
            if (kotlinIssue != null) {
                var modInfo = ModLoadingContext.get().getActiveContainer().getModInfo();
                bus.addListener((FMLConstructModEvent event) -> event.enqueueWork(() -> {
                    throw new ModLoadingException(
                            modInfo,
                            ModLoadingStage.CONSTRUCT,
                            "{3}",
                            kotlinIssue.cause(),
                            kotlinIssue.displayMessage());
                }));
                return;
            }
            dev.compixel.platform.internal.ComposeRuntime.requireAvailable();
            dev.compixel.platform.internal.SkikoNatives.install();
            dev.compixel.ui.ore.internal.CompixelFont.preload();
            ClientBootstrap.INSTANCE.register(bus);
        });
    }
}
