package dev.compixel.forge;

import dev.compixel.forge.sync.MenuSyncNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Server-safe entry point. No Kotlin, Compose, AWT or Skiko initialization. */
@Mod("compixel")
public final class CompixelCommon {
    public CompixelCommon(IEventBus bus) {
        bus.addListener(MenuSyncNetworking::register);
        NeoForge.EVENT_BUS.addListener(MenuSyncNetworking::opened);
        NeoForge.EVENT_BUS.addListener(MenuSyncNetworking::tick);
        NeoForge.EVENT_BUS.addListener(MenuSyncNetworking::stopped);
    }
}
