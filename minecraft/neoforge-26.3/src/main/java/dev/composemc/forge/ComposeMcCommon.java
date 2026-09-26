package dev.composemc.forge;

import dev.composemc.forge.sync.MenuSyncNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Server-safe entry point. No Kotlin, Compose, AWT or Skiko initialization. */
@Mod("composemc")
public final class ComposeMcCommon {
    public ComposeMcCommon(IEventBus bus) {
        bus.addListener(MenuSyncNetworking::register);
        NeoForge.EVENT_BUS.addListener(MenuSyncNetworking::opened);
        NeoForge.EVENT_BUS.addListener(MenuSyncNetworking::tick);
        NeoForge.EVENT_BUS.addListener(MenuSyncNetworking::stopped);
    }
}
