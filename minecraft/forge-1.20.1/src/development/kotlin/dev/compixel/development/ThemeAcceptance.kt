package dev.compixel.development

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.forge.theme.ColorSchemes
import dev.compixel.host.UiSession
import dev.compixel.testing.suite.AcceptanceLog
import dev.compixel.testing.suite.AcceptanceStep
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.editor.OreColorExport
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.layout.OreScreen
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme
import dev.compixel.ui.theme.ColorSchema
import dev.compixel.ui.theme.LocalColorEditor
import dev.compixel.ui.theme.SchemeOwner
import dev.compixel.ui.theme.colors
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Exercises the installed adapter's resource stacks, player colors, color editor and exported pack on a live screen.
 */
internal class ThemeAcceptance(
    private val session: SuiteSession,
    private val script: SuiteScript,
    private val log: AcceptanceLog,
) {
    private val minecraft
        get() = Minecraft.getInstance()

    /** A mod's own colors, as a consumer declares them; its design gives Ore's controls their colors from these. */
    private object FixtureColors : ColorSchema("compixel_theme_test") {
        val panel = color("panel", "surfaces", Color(0xFF203040))
        val accent = color("accent", "accent", Color(0xFF3C8527))
        val accentHover = derived("accentHover", "accent", accent) { lerp(it[accent], Color.Black, .16f) }
    }

    private val design =
        object : UiDesign {
            override val owner = SchemeOwner("compixel_theme_test", FixtureColors, default = "storage")

            @Composable
            override fun Decorate(content: @Composable () -> Unit) {
                val colors = owner.colors()
                val ore =
                    OreColors.values(
                        OreColors.panel to colors[FixtureColors.panel],
                        OreColors.primary to colors[FixtureColors.accent],
                    )
                OreTheme(ore) { CompositionLocalProvider(LocalFixtureColors provides colors, content = content) }
            }

            override fun preview(): @Composable () -> Unit = { OreScreen("Preview") {} }
        }

    private val LocalFixtureColors = staticCompositionLocalOf { FixtureColors.defaults }
    private val owner = design.owner
    private var colors = FixtureColors.defaults
    private var orePrimary = Color.Unspecified
    private val packs = mutableListOf<File>()
    private var originalPacks: List<String>? = null
    private lateinit var screen: ComposeScreen<Unit, Nothing>
    private lateinit var ui: UiSession
    private var reload: CompletableFuture<Void>? = null
    private var creations = 0
    private var draft = ""
    private var edit: (String) -> Unit = {}
    private var openEditor: (() -> Unit)? = null

    fun schedule() {
        script.act("open the color scheme fixture") {
            // A previous run's choice for this owner would decide the scheme before the packs do.
            ColorSchemes.store.select(owner, null)
            screen =
                object : ComposeScreen<Unit, Nothing>(Component.literal("Color schemes"), design = design) {
                    override fun snapshot() {}

                    override fun handle(action: Nothing) {}

                    @Composable
                    override fun Content(state: Unit) {
                        var text by remember {
                            creations++
                            mutableStateOf("")
                        }
                        val current = LocalFixtureColors.current
                        val primary = OreTheme.colors[OreColors.primary]
                        val editor = LocalColorEditor.current
                        SideEffect {
                            colors = current
                            orePrimary = primary
                            draft = text
                            edit = { text = it }
                            openEditor = editor
                        }
                        OreScreen("Color schemes") {
                            OreTextField(text, { text = it }, Modifier.fillMaxWidth(), label = "Draft survives reload")
                            OreButton("Primary action", {}, Modifier.fillMaxWidth())
                        }
                    }

                    override fun isUiWindowFocused() = true
                }
            session.open(screen)
            ui = checkNotNull(screen.session)
        }
        script.until("scheme fixture rendered") { screen.rendererStatistics.renderedFrames > 0 }
        script.act("install two resource-pack layers") {
            ComposeThread.call { edit("unsaved draft") }
            originalPacks = minecraft.resourcePackRepository.selectedIds.toList()
            repeat(2) { packs += newPack("compixel-theme-test-${UUID.randomUUID()}-$it") }
            write(0, "schemes/storage.json", """{"format":1,"colors":{"panel":"#CFD4E2"}}""")
            write(1, "schemes/storage.json", """{"format":1,"colors":{"panel":"#E2DACA","accent":"#426B99"}}""")
            write(
                1,
                "schemes/other.json",
                """{"format":1,"name":"Other","extends":"storage","colors":{"panel":"#223344"}}""",
            )
            select(packs)
        }
        awaitColors("layered schemes", Color(0xFFE2DACA)) {
            check(colors[FixtureColors.accent] == Color(0xFF426B99)) { "The top pack's accent did not apply" }
            check(colors[FixtureColors.accentHover] == lerp(Color(0xFF426B99), Color.Black, .16f))
            check(orePrimary == colors[FixtureColors.accent]) { "Ore's controls did not follow the mod's colors" }
            // The owner's default is listed first, then the other files by path.
            check(ColorSchemes.current.paths(owner) == listOf("storage", "other"))
        }
        script.act("make another scheme the default and break a color of the top file") {
            write(1, "schemes.json", """{"format":1,"default":"other"}""")
            write(
                1,
                "schemes/storage.json",
                """{"format":1,"colors":{"panel":"#E2DACA","typo":"#FFFFFF","accent":"blue"}}""",
            )
            reload = minecraft.reloadResourcePacks()
        }
        awaitColors("index default", Color(0xFF223344)) {
            // The broken accent leaves the lower files and the schema's own accent in effect.
            check(colors[FixtureColors.accent] == FixtureColors.defaults[FixtureColors.accent])
        }
        script.act("choose and change colors as the player") {
            ColorSchemes.store.select(owner, "storage")
            ColorSchemes.store.edit(owner, "storage", FixtureColors.panel, Color(0xFF5A6B7C))
        }
        awaitColors("player colors", Color(0xFF5A6B7C)) {}
        script.act("open the color editor from the screen") {
            ComposeThread.call { checkNotNull(openEditor).invoke() }
            // The screen runs its content's requests at its next tick. Running that tick here lets the suite follow
            // the editor at once; the screen it replaced renders no frame to wait for.
            screen.tick()
            session.adopt(SuitePlatform.screen as? ColorEditorScreen ?: error("The color editor did not open"))
        }
        script.act("export the player's colors as a new default scheme") {
            val editor = SuitePlatform.screen as ColorEditorScreen
            val message = ComposeThread.call { editor.export(OreColorExport("storage", "Acceptance", true, true)) }
            val exported = File(minecraft.resourcePackDirectory.toFile(), message.substringAfterLast('/'))
            check(File(exported, "assets/${owner.namespace}/compixel/schemes/acceptance.json").isFile) {
                "The export wrote no scheme: $message"
            }
            packs += exported
        }
        script.act("close the color editor") {
            (SuitePlatform.screen as ColorEditorScreen).onClose()
            check(SuitePlatform.screen === screen) { "The color editor did not return to its screen" }
            session.adopt(screen)
        }
        script.act("follow the exported pack instead of the player's colors") {
            ColorSchemes.store.restore(owner, "storage")
            ColorSchemes.store.select(owner, null)
            select(packs)
        }
        awaitColors("exported pack", Color(0xFF5A6B7C), sameSession = false) {
            check(ColorSchemes.current.selected(owner) == "acceptance")
        }
        script.act("remove the scheme packs") {
            minecraft.resourcePackRepository.setSelected(checkNotNull(originalPacks))
            reload = minecraft.reloadResourcePacks()
        }
        awaitColors("removed schemes", FixtureColors.defaults[FixtureColors.panel], sameSession = false) {
            check(colors == FixtureColors.defaults)
        }
        script.act("finish color scheme acceptance") {
            session.open(SuiteParentScreen())
            session.requireReleased(screen)
            release()
            log.pass(AcceptanceStep.THEME_RELOAD)
        }
    }

    private fun awaitColors(name: String, panel: Color, sameSession: Boolean = true, verify: () -> Unit) {
        script.until("$name reload completed", 120_000) {
            reload?.isDone != false && !SuitePlatform.overlayActive
        }
        script.act("$name reload result") { reload?.join() }
        script.until("$name reached Compose", 20_000) { ComposeThread.call { colors[FixtureColors.panel] == panel } }
        script.pause(150)
        script.act("$name keeps the composition") {
            ComposeThread.call {
                if (sameSession) {
                    check(screen.session === ui)
                    check(creations == 1 && draft == "unsaved draft") { "A scheme change lost remembered state" }
                }
                verify()
            }
            session.capture("theme-" + name.replace(' ', '-')) { pixels ->
                val expectedRgb = panel.toArgb() and 0xFFFFFF
                var matched = 0
                for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                    if (pixels.rgb(x, y) == expectedRgb) matched++
                }
                check(matched > 100) { "$name: the panel color did not reach the framebuffer" }
            }
        }
        script.until("$name capture completed") { session.capturesIdle }
    }

    private fun newPack(name: String): File {
        val pack = File(minecraft.resourcePackDirectory.toFile(), name)
        check(pack.mkdirs())
        File(pack, "pack.mcmeta")
            .writeText(
                """{"pack":{"pack_format":34,"supported_formats":[0,9999],"min_format":0,"max_format":9999,"description":"CompixelUI scheme acceptance"}}"""
            )
        return pack
    }

    private fun write(priority: Int, path: String, json: String) {
        val file = File(packs[priority], "assets/${owner.namespace}/compixel/$path")
        file.parentFile.mkdirs()
        file.writeText(json)
    }

    private fun select(packs: List<File>) {
        minecraft.resourcePackRepository.reload()
        minecraft.resourcePackRepository.setSelected(checkNotNull(originalPacks) + packs.map { "file/${it.name}" })
        check(packs.all { "file/${it.name}" in minecraft.resourcePackRepository.selectedIds })
        reload = minecraft.reloadResourcePacks()
    }

    fun release() {
        originalPacks?.let { minecraft.resourcePackRepository.setSelected(it) }
        originalPacks = null
        ColorSchemes.store.restore(owner, "storage")
        ColorSchemes.store.select(owner, null)
        // Only the unique directories created by this fixture and its export are owned here.
        packs.forEach { it.deleteRecursively() }
        packs.clear()
    }
}
