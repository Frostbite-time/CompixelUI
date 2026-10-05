package dev.compixel.forge

import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.theme.ThemeReloadListener
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.IEventBus

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        MinecraftForge.EVENT_BUS.addListener(dev.compixel.forge.sync.ClientMenuSync::tick)
        MinecraftForge.EVENT_BUS.addListener(::tick)
        MinecraftForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ThemeReloadListener)
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // HUD layers publish their content's state once per client tick.
    private fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) tickHudLayers()
    }

    // HUD layers release their sessions with the world; the next drawn frame opens new ones.
    private fun leaveWorld(event: ClientPlayerNetworkEvent.LoggingOut) = closeHudLayers()
}
