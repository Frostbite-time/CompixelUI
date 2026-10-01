package dev.compixel.forge

import dev.compixel.forge.render.FrameRetirement
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.theme.OreThemeReloadListener
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.compixel.forge.sync.ClientMenuSync::tick)
        NeoForge.EVENT_BUS.addListener(FrameRetirement::finish)
        NeoForge.EVENT_BUS.addListener(FrameRetirement::shutdown)
        NeoForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
        modEventBus.addListener(dev.compixel.forge.drawing.NativePictureRenderers::registered)
    }

    private fun registerReloadListeners(event: AddClientReloadListenersEvent) {
        event.addListener(Identifier.fromNamespaceAndPath("compixel", "ore_themes"), OreThemeReloadListener)
        event.addListener(
            Identifier.fromNamespaceAndPath("compixel", "renderer_resources"),
            ResourceManagerReloadListener { RendererResources.reloaded() },
        )
    }

    // HUD layers release their sessions with the world; the next drawn frame opens new ones.
    private fun leaveWorld(event: ClientPlayerNetworkEvent.LoggingOut) = closeHudLayers()
}
