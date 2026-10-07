package dev.compixel.forge

import dev.compixel.forge.constants.CompixelGuiLayers
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.forge.theme.ColorSchemes
import dev.compixel.forge.theme.SchemeReloadListener
import dev.compixel.ui.ore.theme.OreDesign
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.ModLoadingContext

internal object ClientBootstrap {
    fun register(modEventBus: IEventBus) {
        // The player's scheme choices and colors, which the color editor saves.
        ColorSchemes.store.reload()
        // The mod list's Config button opens the color editor for CompixelUI's own Ore schemes.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
            ConfigScreenHandler.ConfigScreenFactory { parent -> ColorEditorScreen(parent, OreDesign()) }
        }
        MinecraftForge.EVENT_BUS.addListener(dev.compixel.forge.sync.ClientMenuSync::tick)
        MinecraftForge.EVENT_BUS.addListener(::tick)
        MinecraftForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
        modEventBus.addListener(::registerOverlays)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(SchemeReloadListener)
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // Screens play their exits above every HUD overlay.
    private fun registerOverlays(event: RegisterGuiOverlaysEvent) =
        event.registerAboveAll(CompixelGuiLayers.SCREEN_EXITS.path, ScreenExits)

    // HUD layers publish their content's state once per client tick; covered screens and exits follow their menus.
    private fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
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
