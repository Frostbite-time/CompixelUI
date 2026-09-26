package dev.composemc.development

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.forge.ComposeScreen
import dev.composemc.forge.render.RendererResources
import dev.composemc.forge.render.configuredRenderBackend
import dev.composemc.render.RenderBackend
import dev.composemc.testing.suite.ScreenPixels
import net.minecraft.client.InactivityFpsLimit
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.KeyboardHandler
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.RenderTooltipEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.function.Consumer

internal typealias SuiteComposeScreen = ComposeScreen

/** Every Minecraft 26.1.2 / NeoForge call the shared suite code needs, in one reviewable place. */
internal object SuitePlatform {
    const val MINECRAFT = "26.1.2"
    const val LOADER = "NeoForge"
    const val MOUSE_LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT
    const val MOUSE_RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT
    const val KEY_A = GLFW.GLFW_KEY_A
    const val KEY_BACKSPACE = GLFW.GLFW_KEY_BACKSPACE
    const val KEY_ESCAPE = GLFW.GLFW_KEY_ESCAPE
    const val MOD_CONTROL = GLFW.GLFW_MOD_CONTROL

    private val minecraft get() = Minecraft.getInstance()
    private val window get() = minecraft.window.handle()

    val loaderProduction: Boolean get() = FMLEnvironment.isProduction()
    val backend: RenderBackend get() = configuredRenderBackend()
    val overlayActive: Boolean get() = minecraft.overlay != null
    val screen: Screen? get() = minecraft.screen
    val guiScale: Int get() = minecraft.window.guiScale
    val resourceEpoch: Long get() = RendererResources.epoch
    val windowHidden: Boolean get() = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE
    val windowFocused: Boolean get() = minecraft.window.isFocused
    /** Whether the Compose host opened Minecraft's text input, which keeps the IME available. */
    fun textInputOpen(screen: Screen): Boolean = (screen as? SuiteComposeScreen)?.textInputOpen == true
    /** Input method composition arrives as preedit events; [preedit] submits one as KeyboardHandler does. */
    val preeditSupported: Boolean get() = true
    fun preedit(screen: Screen, text: String?) =
        KeyboardHandler.submitPreeditEvent(screen, text?.let { PreeditEvent(it, it.length, listOf(it), 0) })

    fun setScreen(screen: Screen?) = minecraft.setScreen(screen)
    fun defer(task: () -> Unit) = minecraft.schedule(Runnable(task))
    fun clearToasts() = minecraft.toastManager.clear()
    /** The translation key of the category that holds the key mapping [name]. */
    fun keyCategory(name: String): String? = minecraft.options.keyMappings.firstOrNull { it.name == name }
        ?.category?.label()?.contents?.let { (it as? TranslatableContents)?.key }

    fun setGuiScale(scale: Int) {
        minecraft.options.guiScale().set(scale)
        minecraft.resizeGui()
    }

    fun setWindowSize(width: Int, height: Int) = GLFW.glfwSetWindowSize(window, width, height)

    fun hideWindow() {
        GLFW.glfwHideWindow(window)
        GLFW.glfwPollEvents()
    }

    /** Posts the same Screen key event a real F8 press produces; returns whether a handler consumed it. */
    fun pressPreviewKey(parent: Screen): Boolean {
        val event = ScreenEvent.KeyPressed.Pre(parent, KeyEvent(GLFW.GLFW_KEY_F8, 0, 0))
        return NeoForge.EVENT_BUS.post(event).isCanceled
    }

    fun cancelTooltips(active: () -> Boolean): AutoCloseable {
        val listener = Consumer<RenderTooltipEvent.Pre> { if (active()) it.isCanceled = true }
        NeoForge.EVENT_BUS.addListener(listener)
        return AutoCloseable { NeoForge.EVENT_BUS.unregister(listener) }
    }

    /** Copies the last completed frame; 26.x delivers the pixels asynchronously on the render thread. */
    fun screenshot(file: File, done: (ScreenPixels) -> Unit) {
        Screenshot.takeScreenshot(minecraft.mainRenderTarget) { image ->
            image.use {
                it.writeToFile(file)
                done(ScreenPixels(it.width, it.height, IntArray(it.width * it.height) { index -> it.getPixel(index % it.width, index / it.width) }))
            }
        }
    }

    /** Pins pacing, layout and background work for the suites and returns the restore action. */
    fun prepareOptions(frameLimit: Int, width: Int, height: Int, guiScale: Int): () -> Unit {
        val options = minecraft.options
        val vsync = options.enableVsync().get()
        val limit = options.framerateLimit().get()
        val scale = options.guiScale().get()
        val distance = options.renderDistance().get()
        val inactivity = options.inactivityFpsLimit().get()
        val master = options.getSoundSourceOptionInstance(SoundSource.MASTER).get()
        val pause = options.pauseOnLostFocus
        options.enableVsync().set(false)
        options.framerateLimit().set(frameLimit)
        options.renderDistance().set(2)
        // The AFK limiter only sees real keyboard and mouse events, never the suites' Screen callbacks.
        options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0)
        options.pauseOnLostFocus = false
        setWindowSize(width, height)
        setGuiScale(guiScale)
        return {
            options.enableVsync().set(vsync)
            options.framerateLimit().set(limit)
            options.renderDistance().set(distance)
            options.inactivityFpsLimit().set(inactivity)
            options.getSoundSourceOptionInstance(SoundSource.MASTER).set(master)
            options.pauseOnLostFocus = pause
            setGuiScale(scale)
        }
    }

    fun device(): Map<String, Any?> = RenderSystem.getDevice().let {
        linkedMapOf("gpu" to it.renderer, "vendor" to it.vendor, "driver" to it.version, "api" to it.backendName)
    }

    fun resourcePacks(): List<String> = minecraft.resourcePackRepository.selectedPacks.map { it.id }

    fun createFlatWorld(name: String, parent: Screen) {
        val directory = minecraft.levelSource.baseDir.resolve(name).toFile()
        check(!directory.exists() || directory.deleteRecursively()) { "Cannot delete the previous test world $directory" }
        val settings = LevelSettings(name, GameType.CREATIVE, LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
            true, WorldDataConfiguration.DEFAULT)
        minecraft.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions(42L, false, false), { registries ->
            registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
        }, parent)
    }
}
