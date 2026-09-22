package dev.composemc.neoforge

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.composemc.neoforge.sync.ClientMenuSync::tick)
        modEventBus.addListener(::registerReloadListeners)
    }
    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }
}
