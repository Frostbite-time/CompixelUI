package dev.compixel.testing.ui

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.compixel.bridge.ComposeThread
import dev.compixel.host.UiSession
import dev.compixel.platform.Viewport
import dev.compixel.ui.ore.overlay.OreDialog
import dev.compixel.ui.ore.overlay.OreTextContextMenu
import dev.compixel.ui.ore.theme.*
import dev.compixel.ui.theme.ColorValues
import dev.compixel.ui.theme.LocalSchemes
import dev.compixel.ui.theme.SchemeCatalog
import dev.compixel.ui.theme.SchemeFile
import dev.compixel.ui.theme.SchemeId
import dev.compixel.ui.theme.SchemeSettings
import dev.compixel.ui.theme.Schemes
import kotlin.test.*
import org.junit.jupiter.api.Test

class OreThemeTest {
    private val owner = OreDesign().owner

    private fun pack(path: String, vararg colors: Pair<String, String>) =
        SchemeCatalog.create(
            mapOf(
                SchemeId("compixel", path) to
                    listOf(SchemeFile("pack", mapOf("format" to 1, "colors" to mapOf(*colors))))
            ),
            emptyMap(),
        ) {
            error(it)
        }

    @Test
    fun `CompixelUI offers the built-in schemes and a pack changes one while keeping the rest`() {
        val schemes = Schemes(pack("light", "panel" to "#EEEEEE"), SchemeSettings())
        assertEquals(listOf("default", "light", "twilight"), schemes.paths(owner))
        assertEquals(OreColors.defaults, schemes.colors(owner))
        val light = schemes.colors(owner, "light")
        assertEquals(Color(0xFFEEEEEE), light[OreColors.panel])
        assertEquals(OreColors.scheme("light")[OreColors.text], light[OreColors.text])
        assertEquals(OreColors.scheme("light")[OreColors.primaryHover], light[OreColors.primaryHover])
        assertReadable(OreColors.scheme("light"))
        assertReadable(OreColors.scheme("twilight"))
    }

    @Test
    fun `a pack that changes a base color recolors the shades that follow it`() {
        val light = Schemes(pack("light", "primary" to "#3355AA"), SchemeSettings()).colors(owner, "light")
        val primary = Color(0xFF3355AA)
        assertEquals(primary, light[OreColors.trackFilled])
        assertEquals(lerp(primary, Color.Black, .16f), light[OreColors.primaryHover])
        assertEquals(lerp(light[OreColors.slot], primary, .22f), light[OreColors.markedSlot])
        assertTrue(light.follows(OreColors.primaryHover))
        // Colors that do not follow the primary keep Light's own.
        assertEquals(OreColors.scheme("light")[OreColors.dangerHover], light[OreColors.dangerHover])
    }

    @Test
    fun `the theme gives text fields Ore's selection and menu`() {
        var selection = Color.Unspecified
        var menu: Any? = null
        UiSession(Viewport(40, 40)) {
                OreTheme(OreColors.scheme("twilight")) {
                    val colors = LocalTextSelectionColors.current
                    val representation = LocalContextMenuRepresentation.current
                    SideEffect {
                        selection = colors.backgroundColor
                        menu = representation
                    }
                }
            }
            .use { session -> session.frame(1_000_000_000L)?.close() }
        ComposeThread.call {
            assertEquals(OreColors.scheme("twilight")[OreColors.selection], selection)
            assertSame(OreTextContextMenu, menu)
        }
    }

    private fun assertReadable(colors: ColorValues) {
        fun contrast(a: Color, b: Color): Float =
            (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
        for ((foreground, background) in
            listOf(
                OreColors.text to OreColors.panel,
                OreColors.mutedText to OreColors.raised,
                OreColors.onPrimary to OreColors.primary,
                OreColors.onPrimary to OreColors.primaryHover,
                OreColors.onSecondary to OreColors.secondary,
                OreColors.onDanger to OreColors.danger,
            )) {
            assertTrue(contrast(colors[foreground], colors[background]) >= 4.5f, "$foreground on $background")
        }
    }

    @Test
    fun `live scheme changes preserve remembered state and reach dialogs popups and nested themes`() {
        val state = ComposeThread.call { mutableStateOf(Schemes.Empty) }
        var creations = 0
        var observed = emptyList<ColorValues>()
        var dialogColor = Color.Unspecified
        var popupColor = Color.Unspecified
        var draft = ""
        var edit: (String) -> Unit = {}
        val design = OreDesign()
        UiSession(Viewport(320, 240)) {
                CompositionLocalProvider(LocalSchemes provides state.value) {
                    design.Decorate {
                        var text by remember {
                            creations++
                            mutableStateOf("")
                        }
                        val own = OreTheme.colors
                        var other = own
                        OreTheme(OreColors.scheme("twilight")) { other = OreTheme.colors }
                        SideEffect {
                            observed = listOf(own, other)
                            draft = text
                            edit = { text = it }
                        }
                        OreDialog("Theme", {}, buttons = {}) {
                            val color = OreTheme.colors[OreColors.panel]
                            SideEffect { dialogColor = color }
                            Box(Modifier.size(12.dp))
                        }
                        Popup {
                            val color = OreTheme.colors[OreColors.panel]
                            SideEffect { popupColor = color }
                            Box(Modifier.size(12.dp))
                        }
                    }
                }
            }
            .use { session ->
                var time = 1_000_000_000L
                fun settle() {
                    repeat(6) {
                        time += 20_000_000L
                        session.frame(time)?.close()
                    }
                }
                val light = SchemeSettings().with(design.owner, SchemeSettings.Choice(scheme = "light"))
                settle()
                ComposeThread.call {
                    edit("unsaved draft")
                    state.value = Schemes(SchemeCatalog.Empty, light)
                }
                settle()
                ComposeThread.call {
                    val panel = OreColors.scheme("light")[OreColors.panel]
                    assertEquals(1, creations)
                    assertEquals("unsaved draft", draft)
                    assertEquals(listOf(OreColors.scheme("light"), OreColors.scheme("twilight")), observed)
                    assertEquals(panel, dialogColor)
                    assertEquals(panel, popupColor)
                    state.value = Schemes(SchemeCatalog.Empty, light)
                }
                assertNull(session.frame(time + 20_000_000L), "Equal schemes must retain the recorded frame")
            }
    }
}
