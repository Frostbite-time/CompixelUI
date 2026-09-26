package dev.composemc.forge

import dev.composemc.forge.render.FrameRetirement
import dev.composemc.forge.render.RendererResources
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(dev.composemc.forge.sync.ClientMenuSync::tick)
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
