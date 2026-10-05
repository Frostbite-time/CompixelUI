package dev.compixel.forge

import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.theme.ThemeReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.compixel.forge.sync.ClientMenuSync::tick)
        NeoForge.EVENT_BUS.addListener(::tick)
        NeoForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
        modEventBus.addListener(::registerLayers)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ThemeReloadListener)
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // Screens play their exits above every HUD layer.
    private fun registerLayers(event: RegisterGuiLayersEvent) =
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("compixel", "screen_exits"), ScreenExits)

    // HUD layers publish their content's state once per client tick; covered screens and exits follow their menus.
    private fun tick(event: ClientTickEvent.Post) {
        tickHudLayers()
        CoveredScreens.tick()
        ScreenExits.tick()
    }

    // HUD layers, covered screens and exits release their sessions with the world; the next drawn frame opens new HUDs.
    private fun leaveWorld(event: ClientPlayerNetworkEvent.LoggingOut) {
        closeHudLayers()
        CoveredScreens.closeAll()
        ScreenExits.closeAll()
    }
}
