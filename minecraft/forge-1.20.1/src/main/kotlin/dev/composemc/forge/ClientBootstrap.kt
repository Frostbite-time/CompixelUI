package dev.composemc.forge

import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.common.MinecraftForge
import dev.composemc.forge.render.RendererResources

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        MinecraftForge.EVENT_BUS.addListener(dev.composemc.forge.sync.ClientMenuSync::tick)
        modEventBus.addListener(::registerReloadListeners)
    }
    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }
}
