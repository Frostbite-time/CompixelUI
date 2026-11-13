package dev.compixel.development

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.resources.fontResource
import dev.compixel.forge.resources.painterResource
import dev.compixel.forge.theme.ColorEditorScreen
import dev.compixel.forge.theme.ColorSchemes
import dev.compixel.host.UiSession
import dev.compixel.testing.ResourceFontFixture
import dev.compixel.testing.suite.AcceptanceLog
import dev.compixel.testing.suite.AcceptanceStep
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.editor.OreColorExport
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.layout.OreScreen
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme
import dev.compixel.ui.theme.ColorSchema
import dev.compixel.ui.theme.LocalColorEditor
import dev.compixel.ui.theme.SchemeOwner
import dev.compixel.ui.theme.colors
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.repository.Pack

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
    private lateinit var screen: ComposeScreen
    private lateinit var ui: UiSession
    private var reload: CompletableFuture<Void>? = null
    private var creations = 0
    private var draft = ""
    private var edit: (String) -> Unit = {}
    private var openEditor: (() -> Unit)? = null
    private var imageBounds: Rect? = null
    private var fadedBounds: Rect? = null
    private var backdropBounds: Rect? = null
    private var imageSize = Size.Zero
    private var imageColor = BASE_IMAGE
    private var expectedImageSize = Size(8f, 4f)
    private var loadedFont: FontFamily? = null
    private var oreFont: FontFamily? = null
    private var fontWidth = 0
    private var baseFontWidth = 0
    private var smallerFont = false

    fun schedule() {
        script.act("open the color scheme fixture") {
            // A previous run's choice for this owner would decide the scheme before the packs do.
            ColorSchemes.store.select(owner, null)
            screen =
                object : ComposeScreen(Component.literal("Color schemes"), design = design) {
                    @Composable
                    override fun Content() {
                        var text by remember {
                            creations++
                            mutableStateOf("")
                        }
                        val current = LocalFixtureColors.current
                        val primary = OreTheme.colors[OreColors.primary]
                        val editor = LocalColorEditor.current
                        val resourceFont = fontResource(ResourceFontFixture.PATH)
                        val textStyle = OreTheme.typography.body
                        val measuredFontWidth = rememberTextMeasurer().measure("Resource font", textStyle).size.width
                        SideEffect {
                            loadedFont = resourceFont
                            oreFont = textStyle.fontFamily
                            fontWidth = measuredFontWidth
                            colors = current
                            orePrimary = primary
                            draft = text
                            edit = { text = it }
                            openEditor = editor
                        }
                        OreScreen("Color schemes") {
                            OreTextField(text, { text = it }, Modifier.fillMaxWidth(), label = "Draft survives reload")
                            OreButton("Primary action", {}, Modifier.fillMaxWidth())
                            ResourceImages()
                            OreText("Resource font")
                        }
                    }

                    override fun isUiWindowFocused() = true
                }
            session.open(screen)
            ui = checkNotNull(screen.session)
        }
        script.until("scheme fixture rendered") { screen.rendererStatistics.renderedFrames > 0 }
        script.act("install two resource-pack layers") {
            baseFontWidth = fontWidth
            check(baseFontWidth > 0 && loadedFont == oreFont) { "The bundled resource font did not reach Ore" }
            val defaults = minecraft.resourcePackRepository.availablePacks.single { it.id == "compixel:defaults" }
            check(defaults.isRequired && defaults.isHidden) { "The default assets must be a required hidden pack" }
            check(defaults.isFixedPosition && defaults.defaultPosition == Pack.Position.BOTTOM) {
                "The default resource pack must stay at the bottom"
            }
            check(nativeFont().contentEquals(ResourceFontFixture.original)) {
                "Minecraft's resource manager did not expose the unpacked default TTF"
            }
            edit("unsaved draft")
            originalPacks = minecraft.resourcePackRepository.selectedIds.toList()
            repeat(2) { packs += newPack("compixel-theme-test-${UUID.randomUUID()}-$it") }
            writeImage(0, LOWER_IMAGE, 8, 4)
            writeImage(1, UPPER_IMAGE, 4, 8)
            writeFont(0, ResourceFontFixture.original)
            writeFont(1, ResourceFontFixture.smaller)
            smallerFont = true
            imageColor = UPPER_IMAGE
            expectedImageSize = Size(4f, 8f)
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
            writeImage(1, EDITED_IMAGE, 6, 3)
            writeFont(0, ResourceFontFixture.smaller)
            writeFont(1, ResourceFontFixture.original)
            smallerFont = false
            imageColor = EDITED_IMAGE
            expectedImageSize = Size(6f, 3f)
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
        script.act("remove the top resource-pack image") {
            check(imageFile(1).delete())
            check(fontFile(1).delete())
            smallerFont = true
            imageColor = LOWER_IMAGE
            expectedImageSize = Size(8f, 4f)
            reload = minecraft.reloadResourcePacks()
        }
        awaitColors("lower image", Color(0xFF5A6B7C)) {}
        script.act("break the remaining resource-pack font") {
            writeFont(0, byteArrayOf(1, 2, 3))
            smallerFont = false
            reload = minecraft.reloadResourcePacks()
        }
        awaitColors("invalid font fallback", Color(0xFF5A6B7C)) {}
        script.act("open the color editor from the screen") {
            checkNotNull(openEditor).invoke()
            // The screen runs its content's requests at its next tick. Running that tick here lets the suite follow
            // the editor at once; the screen it replaced renders no frame to wait for.
            screen.tick()
            session.adopt(SuitePlatform.screen as? ColorEditorScreen ?: error("The color editor did not open"))
        }
        script.act("export the player's colors as a new default scheme") {
            val editor = SuitePlatform.screen as ColorEditorScreen
            val message = editor.export(OreColorExport("storage", "Acceptance", true, true))
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
            imageColor = BASE_IMAGE
            expectedImageSize = Size(8f, 4f)
            minecraft.resourcePackRepository.setSelected(checkNotNull(originalPacks))
            reload = minecraft.reloadResourcePacks()
        }
        awaitColors("removed schemes", FixtureColors.defaults[FixtureColors.panel], sameSession = false) {
            check(colors == FixtureColors.defaults)
            check(nativeFont().contentEquals(ResourceFontFixture.original)) {
                "Minecraft did not restore the built-in font after removing the packs"
            }
        }
        script.act("finish color scheme acceptance") {
            session.open(SuiteParentScreen())
            session.requireReleased(screen)
            release()
            log.pass(AcceptanceStep.RESOURCE_IMAGES)
            log.pass(AcceptanceStep.RESOURCE_FONTS)
            log.pass(AcceptanceStep.THEME_RELOAD)
        }
    }

    private fun awaitColors(name: String, panel: Color, sameSession: Boolean = true, verify: () -> Unit) {
        script.until("$name reload completed", 120_000) {
            reload?.isDone != false && !SuitePlatform.overlayActive
        }
        script.act("$name reload result") { reload?.join() }
        script.until("$name reached Compose", 20_000) { colors[FixtureColors.panel] == panel }
        script.pause(150)
        script.act("$name keeps the composition") {
            if (sameSession) {
                check(screen.session === ui)
                check(creations == 1 && draft == "unsaved draft") { "A scheme change lost remembered state" }
            }
            verify()
            check(imageSize == expectedImageSize) { "Reload kept the old resource image dimensions: $imageSize" }
            check(if (smallerFont) fontWidth in 1 until baseFontWidth else fontWidth == baseFontWidth) {
                "Resource font width $fontWidth did not follow the pack (base $baseFontWidth, smaller $smallerFont)"
            }
            // Invalid resources use Ore's bundled fallback, while the general API uses FontFamily.Default.
            if (loadedFont != FontFamily.Default)
                check(loadedFont == oreFont) { "Ore did not follow the resource font" }
            session.capture("theme-" + name.replace(' ', '-')) { pixels ->
                val expectedRgb = panel.toArgb() and 0xFFFFFF
                var matched = 0
                for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                    if (pixels.rgb(x, y) == expectedRgb) matched++
                }
                check(matched > 100) { "$name: the panel color did not reach the framebuffer" }
                fun rgb(bounds: Rect?) = checkNotNull(bounds).center.let { pixels.rgb(it.x.toInt(), it.y.toInt()) }
                check(rgb(imageBounds) == (imageColor and 0xFFFFFF)) {
                    "$name: the resource image did not reach the framebuffer"
                }
                val faded = rgb(fadedBounds)
                val backdrop = rgb(backdropBounds)
                for (shift in listOf(16, 8, 0)) {
                    val expected = (255 + (backdrop shr shift and 255)) / 2
                    check(kotlin.math.abs((faded shr shift and 255) - expected) <= 2) {
                        "$name: Compose alpha/tint was not applied to the resource image"
                    }
                }
            }
        }
        script.until("$name capture completed") { session.capturesIdle }
    }

    @Composable
    private fun ResourceImages() {
        val image = painterResource(IMAGE_PATH)
        SideEffect { imageSize = image.intrinsicSize }
        Row {
            Image(
                image,
                null,
                Modifier.size(16.dp).onGloballyPositioned { imageBounds = it.boundsInRoot() },
                contentScale = ContentScale.FillBounds,
            )
            Image(
                painterResource(IMAGE_PATH),
                null,
                Modifier.size(16.dp).onGloballyPositioned { fadedBounds = it.boundsInRoot() },
                contentScale = ContentScale.FillBounds,
                alpha = .5f,
                colorFilter = ColorFilter.tint(Color.White),
            )
            Box(Modifier.size(16.dp).onGloballyPositioned { backdropBounds = it.boundsInRoot() })
        }
    }

    private fun imageFile(priority: Int) = File(packs[priority], "assets/compixel_theme_test/textures/ui/panel.png")

    private fun fontFile(priority: Int) = File(packs[priority], "assets/compixel/font/compixel.ttf")

    private fun nativeFont(): ByteArray =
        minecraft.resourceManager
            .listResources("font") { it.toString() == ResourceFontFixture.PATH }
            .values
            .single()
            .open()
            .use { it.readAllBytes() }

    private fun writeFont(priority: Int, bytes: ByteArray) {
        val file = fontFile(priority)
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        file.writeBytes(bytes)
    }

    private fun writeImage(priority: Int, color: Int, width: Int, height: Int) {
        val file = imageFile(priority)
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color)
        check(ImageIO.write(image, "png", file))
    }

    private companion object {
        const val IMAGE_PATH = "compixel_theme_test:textures/ui/panel.png"
        val BASE_IMAGE = 0xFF3060C0.toInt()
        val LOWER_IMAGE = 0xFFC04020.toInt()
        val UPPER_IMAGE = 0xFF20A060.toInt()
        val EDITED_IMAGE = 0xFFE0B030.toInt()
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
