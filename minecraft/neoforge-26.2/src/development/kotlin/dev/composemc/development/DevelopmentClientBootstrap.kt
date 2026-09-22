package dev.composemc.development

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.client.settings.KeyConflictContext
import org.lwjgl.glfw.GLFW

internal object DevelopmentClientBootstrap {
    private val smoke by lazy { if (java.lang.Boolean.getBoolean("composemc.smoke")) ClientSmokeProbe() else null }
    private val benchmark by lazy { if (java.lang.Boolean.getBoolean("composemc.benchmark")) ClientBenchmarkProbe() else null }
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("composemc", "composemc"))
    private val openPreview = KeyMapping(
        "key.composemc.open_preview",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_F8,
        category,
    )

    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::registerKeyMappings)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
        NeoForge.EVENT_BUS.addListener(::afterScreenRender)
        NeoForge.EVENT_BUS.addListener(::onScreenKey)
    }

    private fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(openPreview)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val minecraft = Minecraft.getInstance()
        smoke?.tick()
        benchmark?.tick()
        while (openPreview.consumeClick()) {
            minecraft.setScreenAndShow(ComposePreviewScreen())
        }
    }

    private fun afterScreenRender(event: ScreenEvent.Render.Post) {
        smoke?.afterRender(event.screen)
        benchmark?.afterRender(event.screen)
    }

    private fun onScreenKey(event: ScreenEvent.KeyPressed.Pre) {
        if (openPreview.matches(event.keyEvent)) {
            if (event.screen is ComposePreviewScreen) event.screen.onClose()
            else Minecraft.getInstance().setScreenAndShow(ComposePreviewScreen(event.screen))
            event.isCanceled = true
        }
    }
}

@net.neoforged.fml.common.Mod(value = "composemc_development", dist = [net.neoforged.api.distmarker.Dist.CLIENT])
class ComposeMcDevelopment(bus: IEventBus) { init { DevelopmentClientBootstrap.register(bus) } }
