package dev.composemc.development

import com.google.gson.GsonBuilder
import dev.composemc.bridge.ComposeThread
import dev.composemc.development.render.PortValidationScreen
import dev.composemc.development.render.verifyRenderer
import dev.composemc.neoforge.*
import dev.composemc.render.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import java.io.File

/** Packaged-client acceptance; background runs use the launcher's isolated desktop. */
internal class ClientBenchmarkProbe {
    private val minecraft get() = Minecraft.getInstance()
    private val background = java.lang.Boolean.getBoolean("composemc.benchmark.background")
    private val samples = Integer.getInteger("composemc.benchmark.frames", 120)
    private val cases = listOf(
        BenchmarkCase("static-ui", BenchmarkKind.STATIC),
        BenchmarkCase("animation", BenchmarkKind.ANIMATION),
        BenchmarkCase("list-1k", BenchmarkKind.LIST, 1000),
        BenchmarkCase("list-100k", BenchmarkKind.LIST, 100000),
        BenchmarkCase("native-static", BenchmarkKind.NATIVE_STATIC, 1000),
        BenchmarkCase("native-scroll-10k", BenchmarkKind.NATIVE_SCROLL, 10000),
        BenchmarkCase("rich-tooltip", BenchmarkKind.TOOLTIP),
    ) + dev.composemc.testing.ui.oreComponentPages.map { BenchmarkCase("ore-${it.name.lowercase()}", BenchmarkKind.ORE_COMPONENTS, it.ordinal) }
    private val results = mutableListOf<Map<String, Any?>>()
    private val checks = mutableListOf<String>()
    private var started = false
    private var awaitingWorld = false
    private var inventoryProbe: InventoryAcceptanceProbe? = null
    private var syncProbe: MenuSyncAcceptanceProbe? = null
    private var stage = 0
    private var frames = 0
    private var openedAt = 0L
    private var caseIndex = -1
    private var screen: NeoForgeComposeScreen? = null
    private var pendingCapture = false
    private var captured = false
    private var reload: java.util.concurrent.CompletableFuture<Void>? = null
    private val output get() = File(minecraft.gameDirectory, "benchmark-results").also { it.mkdirs() }

    fun afterRender(parent: Screen) {
        if (minecraft.overlay != null) return
        if (java.lang.Boolean.getBoolean("composemc.benchmark.control")) {
            if (!started) {
                if (background) {
                    GLFW.glfwHideWindow(minecraft.window.handle())
                }
                started = true
                reload = minecraft.reloadResourcePacks()
            }
            if (reload!!.isDone && ++frames == 120) {
                reload!!.join()
                File(minecraft.gameDirectory, "composemc-benchmark.txt").writeText("PASS control: no Compose renderer created\n")
                minecraft.stop()
            }
            return
        }
        if (!started) {
            started = true
            if (background) {
                GLFW.glfwHideWindow(minecraft.window.handle())
                GLFW.glfwPollEvents()
            }
            minecraft.options.enableVsync().set(false)
            minecraft.options.framerateLimit().set(260)
            minecraft.options.guiScale().set(2)
            minecraft.resizeGui()
            checks += verifyRenderer()
            awaitingWorld = true
            minecraft.execute(Runnable { createWorld(parent) })
            return
        }
        if (background) check(GLFW.glfwGetWindowAttrib(minecraft.window.handle(), GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE)
        val active = screen ?: return
        minecraft.toastManager.clear()
        if (parent !== active) return
        frames++
        if (active is PortValidationScreen) {
            validate(active)
            return
        }
        active as BenchmarkScreen
        if (active.fixture.kind == BenchmarkKind.TOOLTIP) {
            ComposeThread.call { active.model.tooltipTarget }?.let { bounds ->
                val scale = minecraft.window.guiScale.toDouble()
                active.mouseMoved(bounds.center.x / scale, bounds.center.y / scale)
            }
        }
        active.advance()
        if (!active.componentsReady) return
        if (active.fixture.kind == BenchmarkKind.TOOLTIP && System.nanoTime() - openedAt < 1_500_000_000L) return
        if (frames < 180 + samples) return
        if (!pendingCapture) {
            active.verifyComponents()
            val rows = active.frameProfiler!!.frames().takeLast(samples)
            check(rows.size == samples)
            if (active.fixture.kind in listOf(BenchmarkKind.STATIC, BenchmarkKind.NATIVE_STATIC))
                check(rows.none { it.rendered }) { "Static UI repainted" }
            if (active.fixture.kind in listOf(BenchmarkKind.ANIMATION, BenchmarkKind.LIST))
                check(rows.count { it.rendered } > samples * 0.8) { "Animation did not advance" }
            if (active.fixture.kind == BenchmarkKind.TOOLTIP)
                check(active.nativeTooltipStatistics.visible) { "Tooltip did not appear" }
            check(active.renderBackend == RenderBackend.CPU_RASTER || active.rendererStatistics.fullFrameUploads == 0L)
            fun summary(values: List<Long>): Map<String, Double> {
                val sorted = values.sorted()
                return mapOf("median_ms" to sorted[sorted.size / 2] / 1e6,
                    "p95_ms" to sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] / 1e6)
            }
            results += mapOf("name" to active.fixture.name, "frames" to rows.size,
                "redraws" to rows.count { it.rendered }, "cpu" to summary(rows.map { it.totalNanos }),
                "render" to summary(rows.map { it.cpu.getValue(CpuPhase.RENDER) }),
                "resources" to active.rendererStatistics)
            capture(active.fixture.name)
        } else if (captured) nextCase()
    }

    fun tick() {
        if (awaitingWorld && minecraft.player != null && minecraft.overlay == null) {
            awaitingWorld = false
            inventoryProbe = InventoryAcceptanceProbe {
                checks += "native left/right container input and server acknowledgement"
                inventoryProbe = null
                syncProbe = MenuSyncAcceptanceProbe {
                    checks += "bounded multi-batch snapshot and fragmented action round trip"
                    syncProbe = null
                    open(PortValidationScreen())
                }
            }
        }
        inventoryProbe?.tick()
        syncProbe?.tick()
    }

    private fun createWorld(parent: Screen) {
        val settings = net.minecraft.world.level.LevelSettings("Compose acceptance",
            net.minecraft.world.level.GameType.CREATIVE,
            net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
            true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT)
        minecraft.createWorldOpenFlows().createFreshLevel("composemc-acceptance-" + System.currentTimeMillis(),
            settings, net.minecraft.world.level.levelgen.WorldOptions(42L, false, false),
            { registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions() }, parent)
    }

    private fun validate(active: PortValidationScreen) {
        if (frames < 40) return
        when (stage) {
            0 -> if (!pendingCapture) capture("orientation-alpha", active) else if (captured) {
                active.mouseClicked(32.0, 32.0, 0)
                active.mouseReleased(32.0, 32.0, 0)
                advanceStage()
            }
            1 -> {
                check(ComposeThread.call { active.model.clicks } == 1) { "Rendered top-left button did not receive its click" }
                active.mouseClicked(active.width / 2.0, 28.0, 0)
                active.mouseReleased(active.width / 2.0, 28.0, 0)
                "Port\u4e2d\uD83D\uDE00".forEach { active.charTyped(it) }
                check(ComposeThread.call { active.model.text } == "Port\u4e2d\uD83D\uDE00") { "Unicode text input was lost" }
                active.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL)
                active.keyReleased(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL)
                active.keyPressed(GLFW.GLFW_KEY_BACKSPACE)
                active.keyReleased(GLFW.GLFW_KEY_BACKSPACE)
                check(ComposeThread.call { active.model.text }.isEmpty()) { "Select-all/backspace shortcut failed" }
                checks += "top-left pointer coordinates and premultiplied alpha"
                checks += "Unicode text entry and modifier/key translation"
                minecraft.options.guiScale().set(3)
                minecraft.resizeGui()
                advanceStage()
            }
            2 -> if (!pendingCapture) capture("gui-scale-3", active) else if (captured) {
                minecraft.options.guiScale().set(2)
                GLFW.glfwSetWindowSize(minecraft.window.handle(), 1000, 700)
                advanceStage()
            }
            3 -> if (!pendingCapture) capture("resize", active) else if (captured) {
                checks += "GUI scaling and framebuffer resize"
                reload = minecraft.reloadResourcePacks()
                advanceStage()
            }
            4 -> if (reload?.isDone == true) {
                reload!!.join()
                if (!pendingCapture) capture("resource-reload", active) else if (captured) {
                    checks += "resource reload"
                    GLFW.glfwSetWindowSize(minecraft.window.handle(), 1280, 960)
                    nextCase()
                }
            }
        }
    }

    private fun advanceStage() { stage++; frames = 0; pendingCapture = false; captured = false }

    private fun capture(name: String, fixture: PortValidationScreen? = null) {
        pendingCapture = true
        Screenshot.takeScreenshot(minecraft.mainRenderTarget) { image ->
            image.use {
                it.writeToFile(File(output, "$name.png"))
                if (fixture != null) fixture.verifyPixels(it, minecraft.window.guiScale)
                else {
                    val colors = HashSet<Int>()
                    for (y in 0 until it.height step 8) for (x in 0 until it.width step 8) colors += it.getPixel(x, y)
                    check(colors.size > 16) { "Blank screenshot: $name" }
                }
            }
            captured = true
        }
    }

    private fun open(next: NeoForgeComposeScreen) {
        val previous = screen
        minecraft.setScreen(next)
        screen = next
        if (previous != null) check(previous.rendererStatistics.liveSurfaces == 0) { "Renderer leaked after screen close" }
        frames = 0; pendingCapture = false; captured = false
        openedAt = System.nanoTime()
    }

    private fun nextCase() {
        caseIndex++
        if (caseIndex < cases.size) open(BenchmarkScreen(cases[caseIndex]))
        else {
            check(results.map { it["name"] } == cases.map { it.name }) { "Incomplete benchmark cases" }
            val gson = GsonBuilder().setPrettyPrinting().create()
            File(output, "report.json").writeText(gson.toJson(mapOf("status" to "PASS",
                "backend" to screen!!.renderBackend, "checks" to checks, "cases" to results,
                "hidden" to background, "isolatedDesktop" to background)))
            File(minecraft.gameDirectory, "composemc-benchmark.txt").writeText("PASS\n" + checks.joinToString("\n"))
            minecraft.stop()
        }
    }
}
