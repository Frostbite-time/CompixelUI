package dev.composemc.neoforge

import net.neoforged.bus.api.IEventBus
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.composemc.neoforge.sync.ClientMenuSync::tick)
        NeoForge.EVENT_BUS.addListener(FrameRetirement::finish)
        NeoForge.EVENT_BUS.addListener(FrameRetirement::shutdown)
        modEventBus.addListener(::registerReloadListeners)
    }
    private fun registerReloadListeners(event: AddClientReloadListenersEvent) {
        event.addListener(
            Identifier.fromNamespaceAndPath("composemc", "renderer_resources"),
            ResourceManagerReloadListener { RendererResources.reloaded() },
        )
    }
}
