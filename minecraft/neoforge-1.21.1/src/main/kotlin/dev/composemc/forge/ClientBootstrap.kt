package dev.composemc.forge

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.neoforge.common.NeoForge
import dev.composemc.forge.render.RendererResources

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.composemc.forge.sync.ClientMenuSync::tick)
        modEventBus.addListener(::registerReloadListeners)
    }
    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }
}
