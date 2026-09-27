package dev.composemc.forge

import dev.composemc.forge.render.RendererResources
import dev.composemc.forge.theme.OreThemeReloadListener
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.IEventBus

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        MinecraftForge.EVENT_BUS.addListener(dev.composemc.forge.sync.ClientMenuSync::tick)
        MinecraftForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(OreThemeReloadListener)
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // HUD layers release their sessions with the world; the next drawn frame opens new ones.
    private fun leaveWorld(event: ClientPlayerNetworkEvent.LoggingOut) = closeHudLayers()
}
