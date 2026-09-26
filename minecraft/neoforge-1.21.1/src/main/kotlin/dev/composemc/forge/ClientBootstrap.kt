package dev.composemc.forge

import dev.composemc.forge.render.RendererResources
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.composemc.forge.sync.ClientMenuSync::tick)
        NeoForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // HUD layers release their sessions with the world; the next drawn frame opens new ones.
    private fun leaveWorld(event: ClientPlayerNetworkEvent.LoggingOut) = closeHudLayers()
}
