package dev.composemc.development

import com.mojang.blaze3d.platform.InputConstants
import dev.composemc.testing.suite.ClientSuite
import dev.composemc.testing.suite.SuitePixels
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW

internal object DevelopmentClientBootstrap {
    private val acceptance by lazy {
        if (SuiteEnvironment.suite == ClientSuite.ACCEPTANCE) ClientAcceptanceProbe() else null
    }
    private val benchmark by lazy {
        if (SuiteEnvironment.suite == ClientSuite.BENCHMARK) ClientBenchmarkProbe() else null
    }
    private val category = KeyMapping.Category(Identifier.fromNamespaceAndPath("composemc", "development"))
    private val openPreview =
        KeyMapping(
            "key.composemc.open_preview",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            category,
        )

    fun register(modEventBus: IEventBus, container: ModContainer) {
        SyncAcceptanceMenu.register(modEventBus)
        ConfigAcceptance.register(container)
        modEventBus.addListener(::registerKeyMappings)
        modEventBus.addListener { event: RegisterMenuScreensEvent ->
            event.register(SyncAcceptanceMenu.TYPE.get(), ::SyncAcceptanceScreen)
        }
        NeoForge.EVENT_BUS.addListener(::onClientTick)
        NeoForge.EVENT_BUS.addListener(::afterScreenRender)
        NeoForge.EVENT_BUS.addListener(::onScreenKey)
    }

    private fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        event.registerCategory(category)
        event.register(openPreview)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        acceptance?.tick()
        benchmark?.tick()
        while (openPreview.consumeClick()) {
            Minecraft.getInstance().setScreenAndShow(ComposePreviewScreen())
        }
    }

    private fun afterScreenRender(event: ScreenEvent.Render.Post) {
        acceptance?.afterRender(event.screen) { color ->
            event.guiGraphics.fill(0, 0, SuitePixels.MARKER_GUI_SIZE, SuitePixels.MARKER_GUI_SIZE, color)
        }
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
class ComposeMcDevelopment(bus: IEventBus, container: ModContainer) {
    init {
        DevelopmentClientBootstrap.register(bus, container)
    }
}
