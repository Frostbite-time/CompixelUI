package dev.compixel.development

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeScreen
import dev.compixel.host.UiSession
import dev.compixel.testing.suite.AcceptanceLog
import dev.compixel.testing.suite.AcceptanceStep
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.layout.OreScreen
import dev.compixel.ui.ore.theme.*
import dev.compixel.ui.theme.ThemeId
import dev.compixel.ui.theme.ThemeSection
import dev.compixel.ui.theme.current
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Exercises the installed adapter's actual resource stacks and live screen on an isolated desktop. */
internal class ThemeAcceptance(
    private val session: SuiteSession,
    private val script: SuiteScript,
    private val log: AcceptanceLog,
) {
    private val minecraft
        get() = Minecraft.getInstance()

    /** A section of the fixture's own, as a mod declares next to Ore's for its own tokens. */
    private object AccentSection : ThemeSection<String>("compixel_theme_test") {
        override val default = "none"

        override fun apply(value: String, layer: Any?): String {
            require(layer is Map<*, *>) { "expected an object" }
            return layer["accent"] as? String ?: value
        }
    }

    private val owner = ThemeId("compixel_theme_test", "storage")
    private val other = ThemeId("compixel_other_test")
    private var accent = ""
    private val packs = mutableListOf<File>()
    private var originalPacks: List<String>? = null
    private lateinit var screen: ComposeScreen<Unit, Nothing>
    private lateinit var ui: UiSession
    private var reload: CompletableFuture<Void>? = null
    private var colors = OreColors()
    private var otherColors = OreColors()
    private var creations = 0
    private var draft = ""
    private var edit: (String) -> Unit = {}

    fun schedule() {
        script.act("open the resource theme fixture") {
            screen =
                object : ComposeScreen<Unit, Nothing>(Component.literal("Resource themes"), theme = owner) {
                    override fun snapshot() {}

                    override fun handle(action: Nothing) {}

                    @Composable
                    override fun Content(state: Unit) {
                        var text by remember {
                            creations++
                            mutableStateOf("")
                        }
                        val current = OreTheme.colors
                        val ownAccent = AccentSection.current(owner)
                        var isolated = current
                        OreTheme(other) { isolated = OreTheme.colors }
                        SideEffect {
                            colors = current
                            accent = ownAccent
                            otherColors = isolated
                            draft = text
                            edit = { text = it }
                        }
                        OreScreen("Resource theme / AE light") {
                            OreTextField(text, { text = it }, Modifier.fillMaxWidth(), label = "Draft survives reload")
                            OreButton("Primary action", {}, Modifier.fillMaxWidth())
                        }
                    }

                    override fun isUiWindowFocused() = true
                }
            session.open(screen)
            ui = checkNotNull(screen.session)
        }
        script.until("theme fixture rendered") { screen.rendererStatistics.renderedFrames > 0 }
        script.act("install two resource-pack layers") {
            ComposeThread.call { edit("unsaved theme draft") }
            originalPacks = minecraft.resourcePackRepository.selectedIds.toList()
            val directory = File(minecraft.gameDirectory, "resourcepacks")
            repeat(2) { priority ->
                val pack = File(directory, "compixel-theme-test-${UUID.randomUUID()}-$priority")
                check(pack.mkdirs())
                packs += pack
                File(pack, "pack.mcmeta")
                    .writeText(
                        """{"pack":{"pack_format":34,"supported_formats":[0,9999],"min_format":0,"max_format":9999,"description":"CompixelUI theme acceptance"}}"""
                    )
            }
            write(0, "compixel_theme_test", "default", """{"format":1,"ore":{"preset":"light"}}""")
            write(
                0,
                "compixel_theme_test",
                "storage",
                """{"format":1,"ore":{"colors":{"panel":"#CFD4E2"}},"compixel_theme_test":{"accent":"lower"}}""",
            )
            write(0, "compixel_other_test", "default", """{"format":1,"ore":{"colors":{"panel":"#223344"}}}""")
            write(
                1,
                "compixel_theme_test",
                "storage",
                """{"format":1,"ore":{"palette":{"primary":"#426B99"},"colors":{"panel":"#E2DACA"}},""" +
                    """"compixel_theme_test":{"accent":"higher"}}""",
            )
            minecraft.resourcePackRepository.reload()
            minecraft.resourcePackRepository.setSelected(checkNotNull(originalPacks) + packs.map { "file/${it.name}" })
            check(packs.all { "file/${it.name}" in minecraft.resourcePackRepository.selectedIds })
            reload = minecraft.reloadResourcePacks()
        }
        awaitReload("layered themes", Color(0xFFE2DACA)) {
            check(colors.primary == Color(0xFF426B99) && colors.trackFilled == colors.primary)
            check(colors.text == OrePalettes.Light.text)
            check(otherColors.panel == Color(0xFF223344))
            check(accent == "higher") { "A mod's own theme section did not follow the pack layers: $accent" }
        }
        script.act("replace the top theme with an invalid Ore section") {
            write(
                1,
                "compixel_theme_test",
                "storage",
                """{"format":1,"ore":{"colors":{"panel":"#000000","typo":"#FFFFFF"}},""" +
                    """"compixel_theme_test":{"accent":"isolated"}}""",
            )
            reload = minecraft.reloadResourcePacks()
        }
        awaitReload("invalid theme fallback", Color(0xFFCFD4E2)) {
            check(colors.primary == OrePalettes.Light.primary)
            check(accent == "isolated") { "An invalid Ore section discarded the file's other sections: $accent" }
        }
        script.act("remove the theme packs") {
            minecraft.resourcePackRepository.setSelected(checkNotNull(originalPacks))
            reload = minecraft.reloadResourcePacks()
        }
        awaitReload("removed themes", OreColors().panel) {
            check(colors == OreColors() && otherColors == OreColors() && accent == AccentSection.default)
        }
        script.act("finish resource theme acceptance") {
            session.open(SuiteParentScreen())
            session.requireReleased(screen)
            release()
            log.pass(AcceptanceStep.THEME_RELOAD)
        }
    }

    private fun awaitReload(name: String, expected: Color, verify: () -> Unit) {
        script.until("$name reload completed", 120_000) { reload?.isDone == true && !SuitePlatform.overlayActive }
        script.act("$name reload result") { checkNotNull(reload).join() }
        script.until("$name reached Compose", 20_000) { ComposeThread.call { colors.panel == expected } }
        script.pause(150)
        script.act("$name preserves the composition") {
            check(screen.session === ui)
            ComposeThread.call {
                check(creations == 1 && draft == "unsaved theme draft") { "Theme reload lost remembered state" }
                verify()
            }
            session.capture("theme-" + name.replace(' ', '-')) { pixels ->
                val expectedRgb = expected.toArgb() and 0xFFFFFF
                var matched = 0
                for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                    if (pixels.rgb(x, y) == expectedRgb) matched++
                }
                check(matched > 100) { "$name: the themed panel did not reach the framebuffer" }
            }
        }
        script.until("$name capture completed") { session.capturesIdle }
    }

    private fun write(priority: Int, namespace: String, name: String, json: String) {
        val file = File(packs[priority], "assets/$namespace/compixel/themes/$name.json")
        file.parentFile.mkdirs()
        file.writeText(json)
    }

    fun release() {
        originalPacks?.let { minecraft.resourcePackRepository.setSelected(it) }
        originalPacks = null
        // Only the unique directories created by this fixture are owned here.
        packs.forEach { it.deleteRecursively() }
        packs.clear()
    }
}
