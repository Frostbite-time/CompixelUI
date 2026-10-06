package dev.compixel.forge

import com.mojang.logging.LogUtils
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.theme.ThemeReloadListener
import dev.compixel.host.UiWarmup
import dev.compixel.ui.ore.misc.OreWarmUpContent
import dev.compixel.ui.ore.theme.OreDesign
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent

internal object ClientBootstrap {
    private val logger = LogUtils.getLogger()

    fun register(modEventBus: IEventBus) {
        MinecraftForge.EVENT_BUS.addListener(dev.compixel.forge.sync.ClientMenuSync::tick)
        MinecraftForge.EVENT_BUS.addListener(::tick)
        MinecraftForge.EVENT_BUS.addListener(::leaveWorld)
        modEventBus.addListener(::registerReloadListeners)
        modEventBus.addListener(::clientSetup)
        modEventBus.addListener(::registerOverlays)
    }

    private fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ThemeReloadListener)
        event.registerReloadListener(ResourceManagerReloadListener { RendererResources.reloaded() })
    }

    // Screens play their exits above every HUD overlay.
    private fun registerOverlays(event: RegisterGuiOverlaysEvent) = event.registerAboveAll("screen_exits", ScreenExits)

    // Compose warms up in the background while the game loads, so the first screen does not stall the game.
    private fun clientSetup(event: FMLClientSetupEvent) =
        UiWarmup.start(OreDesign, content = { OreWarmUpContent() }) { logger.warn("CompixelUI warm-up failed", it) }

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
