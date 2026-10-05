package dev.compixel.development

import com.mojang.blaze3d.platform.InputConstants
import dev.compixel.testing.suite.ClientSuite
import dev.compixel.testing.suite.SuitePixels
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
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
            "key.compixel.open_preview",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.compixel",
        )

    fun register(modEventBus: IEventBus) {
        SyncAcceptanceMenu.register(modEventBus)
        modEventBus.addListener(::registerKeyMappings)
        modEventBus.addListener(::registerHud)
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

    /** The development HUD, above every vanilla overlay, drawn only while a suite step or case shows it. */
    private fun registerHud(event: RegisterGuiOverlaysEvent) {
        event.registerAboveAll("suite_hud") { gui, graphics, partialTick, width, height ->
            if (SuiteHud.enabled) {
                SuiteHud.layer.render(gui, graphics, partialTick, width, height)
                benchmark?.afterHudRender()
            }
        }
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

@net.minecraftforge.fml.common.Mod("compixel_development")
class CompixelDevelopment {
    init {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT)
            DevelopmentClientBootstrap.register(
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().modEventBus
            )
    }
}
