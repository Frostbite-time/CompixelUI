package dev.compixel.development

import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.render.RendererResources
import dev.compixel.forge.render.configuredRenderBackend
import dev.compixel.render.RenderBackend
import dev.compixel.testing.suite.ScreenPixels
import java.io.File
import java.util.function.Consumer
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
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
import org.lwjgl.opengl.GL11

internal typealias SuiteComposeScreen = ComposeScreen

/** Every Minecraft 1.21.1 / NeoForge call the shared suite code needs, in one reviewable place. */
internal object SuitePlatform {
    fun nativeDrawingPreview(context: dev.compixel.forge.drawing.NativeDrawingContext) {
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsAngle(
            context.graphics,
            0,
            0,
            context.width,
            context.height,
            context.height / 3,
            0f,
            0f,
            0f,
            checkNotNull(Minecraft.getInstance().player),
        )
    }

    const val MINECRAFT = "1.21.1"
    const val LOADER = "NeoForge"
    const val MOUSE_LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT
    const val MOUSE_RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT
    const val KEY_A = GLFW.GLFW_KEY_A
    const val KEY_BACKSPACE = GLFW.GLFW_KEY_BACKSPACE
    const val KEY_ESCAPE = GLFW.GLFW_KEY_ESCAPE
    const val MOD_CONTROL = GLFW.GLFW_MOD_CONTROL

    private val minecraft
        get() = Minecraft.getInstance()

    private val window
        get() = minecraft.window.window

    val loaderProduction: Boolean
        get() = FMLEnvironment.production

    val backend: RenderBackend
        get() = configuredRenderBackend()

    val overlayActive: Boolean
        get() = minecraft.overlay != null

    val screen: Screen?
        get() = minecraft.screen

    val guiScale: Int
        get() = minecraft.window.guiScale.toInt()

    /** Whether the vanilla HUD is hidden, as F1 toggles it. */
    var hudHidden: Boolean
        get() = minecraft.options.hideGui
        set(hidden) {
            minecraft.options.hideGui = hidden
        }

    val resourceEpoch: Long
        get() = RendererResources.epoch

    val windowHidden: Boolean
        get() = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE

    val windowFocused: Boolean
        get() = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE

    /** GLFW delivers typed characters without opening text input first. */
    fun textInputOpen(@Suppress("UNUSED_PARAMETER") screen: Screen): Boolean = true

    /** Minecraft 1.21.1 reports no input method composition, so the preview skips it. */
    val preeditSupported: Boolean
        get() = false

    fun preedit(@Suppress("UNUSED_PARAMETER") screen: Screen, @Suppress("UNUSED_PARAMETER") text: String?): Unit =
        throw UnsupportedOperationException("Minecraft 1.21.1 has no preedit events")

    fun setScreen(screen: Screen?) = minecraft.setScreen(screen)

    fun defer(task: () -> Unit) = minecraft.tell(Runnable(task))

    fun clearToasts() = minecraft.toasts.clear()

    /** The translation key of the category that holds the key mapping [name]. */
    fun keyCategory(name: String): String? = minecraft.options.keyMappings.firstOrNull { it.name == name }?.category

    fun setGuiScale(scale: Int) {
        minecraft.options.guiScale().set(scale)
        minecraft.resizeDisplay()
    }

    fun setWindowSize(width: Int, height: Int) = GLFW.glfwSetWindowSize(window, width, height)

    fun hideWindow() {
        GLFW.glfwHideWindow(window)
        GLFW.glfwPollEvents()
    }

    /** Posts the same Screen key event a real F8 press produces; returns whether a handler consumed it. */
    fun pressPreviewKey(parent: Screen): Boolean =
        NeoForge.EVENT_BUS.post(ScreenEvent.KeyPressed.Pre(parent, GLFW.GLFW_KEY_F8, 0, 0)).isCanceled

    fun cancelTooltips(active: () -> Boolean): AutoCloseable {
        val listener = Consumer<RenderTooltipEvent.Pre> { if (active()) it.isCanceled = true }
        NeoForge.EVENT_BUS.addListener(listener)
        return AutoCloseable { NeoForge.EVENT_BUS.unregister(listener) }
    }

    /** Reads the last completed frame synchronously and converts NativeImage's ABGR to ARGB. */
    fun screenshot(file: File, done: (ScreenPixels) -> Unit) {
        Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { image ->
            image.writeToFile(file)
            done(
                ScreenPixels(
                    image.width,
                    image.height,
                    IntArray(image.width * image.height) { index ->
                        val abgr = image.getPixelRGBA(index % image.width, index / image.width)
                        (abgr and 0xFF00FF00.toInt()) or ((abgr and 0xFF) shl 16) or ((abgr ushr 16) and 0xFF)
                    },
                )
            )
        }
    }

    /** Pins pacing, layout and background work for the suites and returns the restore action. */
    fun prepareOptions(frameLimit: Int, width: Int, height: Int, guiScale: Int): () -> Unit {
        val options = minecraft.options
        val vsync = options.enableVsync().get()
        val limit = options.framerateLimit().get()
        val scale = options.guiScale().get()
        val distance = options.renderDistance().get()
        val master = options.getSoundSourceOptionInstance(SoundSource.MASTER).get()
        val pause = options.pauseOnLostFocus
        options.enableVsync().set(false)
        options.framerateLimit().set(frameLimit)
        options.renderDistance().set(2)
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0)
        options.pauseOnLostFocus = false
        setWindowSize(width, height)
        setGuiScale(guiScale)
        return {
            options.enableVsync().set(vsync)
            options.framerateLimit().set(limit)
            options.renderDistance().set(distance)
            options.getSoundSourceOptionInstance(SoundSource.MASTER).set(master)
            options.pauseOnLostFocus = pause
            setGuiScale(scale)
        }
    }

    fun device(): Map<String, Any?> =
        linkedMapOf(
            "gpu" to GL11.glGetString(GL11.GL_RENDERER),
            "vendor" to GL11.glGetString(GL11.GL_VENDOR),
            "driver" to GL11.glGetString(GL11.GL_VERSION),
            "api" to "OpenGL",
        )

    fun resourcePacks(): List<String> = minecraft.resourcePackRepository.selectedPacks.map { it.id }

    fun createFlatWorld(name: String, parent: Screen) {
        val directory = minecraft.levelSource.baseDir.resolve(name).toFile()
        check(!directory.exists() || directory.deleteRecursively()) {
            "Cannot delete the previous test world $directory"
        }
        val settings =
            LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                GameRules(),
                WorldDataConfiguration.DEFAULT,
            )
        minecraft
            .createWorldOpenFlows()
            .createFreshLevel(
                name,
                settings,
                WorldOptions(42L, false, false),
                { registries ->
                    registries
                        .registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions()
                },
                parent,
            )
    }
}
