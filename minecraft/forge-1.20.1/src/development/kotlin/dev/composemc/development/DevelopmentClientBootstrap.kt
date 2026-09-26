package dev.composemc.development

import com.mojang.blaze3d.platform.InputConstants
import dev.composemc.testing.suite.ClientSuite
import dev.composemc.testing.suite.SuitePixels
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.lwjgl.glfw.GLFW

internal object DevelopmentClientBootstrap {
    private val acceptance by lazy {
        if (SuiteEnvironment.suite == ClientSuite.ACCEPTANCE) ClientAcceptanceProbe() else null
    }
    private val benchmark by lazy {
        if (SuiteEnvironment.suite == ClientSuite.BENCHMARK) ClientBenchmarkProbe() else null
    }
    private val openPreview =
        KeyMapping(
            "key.composemc.open_preview",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.composemc",
        )

    fun register(modEventBus: IEventBus) {
        SyncAcceptanceMenu.register(modEventBus)
        ConfigAcceptance.register()
        modEventBus.addListener(::registerKeyMappings)
        modEventBus.addListener { event: FMLClientSetupEvent ->
            event.enqueueWork { MenuScreens.register(SyncAcceptanceMenu.TYPE.get(), ::SyncAcceptanceScreen) }
        }
        MinecraftForge.EVENT_BUS.addListener(::onClientTick)
        MinecraftForge.EVENT_BUS.addListener(::afterScreenRender)
        MinecraftForge.EVENT_BUS.addListener(::onScreenKey)
    }

    private fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(openPreview)
    }

    private fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        acceptance?.tick()
        benchmark?.tick()
        while (openPreview.consumeClick()) {
            if (minecraft.screen == null) minecraft.setScreen(ComposePreviewScreen())
        }
    }

    private fun afterScreenRender(event: ScreenEvent.Render.Post) {
        acceptance?.afterRender(event.screen) { color ->
            // GuiGraphics batches fills; flush so the marker is drawn in this frame, after Compose.
            event.guiGraphics.fill(0, 0, SuitePixels.MARKER_GUI_SIZE, SuitePixels.MARKER_GUI_SIZE, color)
            event.guiGraphics.flush()
        }
        benchmark?.afterRender(event.screen)
    }

    private fun onScreenKey(event: ScreenEvent.KeyPressed.Pre) {
        if (openPreview.matches(event.keyCode, event.scanCode)) {
            if (event.screen is ComposePreviewScreen) event.screen.onClose()
            else Minecraft.getInstance().setScreen(ComposePreviewScreen(event.screen))
            event.isCanceled = true
        }
    }
}

@net.minecraftforge.fml.common.Mod("composemc_development")
class ComposeMcDevelopment {
    init {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT)
            DevelopmentClientBootstrap.register(
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().modEventBus
            )
    }
}
