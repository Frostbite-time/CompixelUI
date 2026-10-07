package dev.compixel.forge

import dev.compixel.forge.constants.CompixelGuiLayers
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.forge.theme.ColorSchemes
import dev.compixel.forge.theme.SchemeReloadListener
import dev.compixel.ui.ore.theme.OreDesign
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus, container: ModContainer) {
        // The player's scheme choices and colors, which the color editor saves.
        ColorSchemes.store.reload()
        // The mod list's Config button opens the color editor for CompixelUI's own Ore schemes.
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { _, parent ->
                ColorEditorScreen(parent, OreDesign())
            },
        )
        NeoForge.EVENT_BUS.addListener(dev.compixel.forge.sync.ClientMenuSync::tick)
        NeoForge.EVENT_BUS.addListener(::tick)
        NeoForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
        modEventBus.addListener(::registerLayers)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(SchemeReloadListener)
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // Screens play their exits above every HUD layer.
    private fun registerLayers(event: RegisterGuiLayersEvent) =
        event.registerAboveAll(CompixelGuiLayers.SCREEN_EXITS, ScreenExits)

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
