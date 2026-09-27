package dev.composemc.testing.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.composemc.bridge.ComposeThread
import dev.composemc.host.UiSession
import dev.composemc.platform.Viewport
import dev.composemc.ui.ore.overlay.OreDialog
import dev.composemc.ui.ore.theme.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class OreThemeTest {
    private fun patch(vararg fields: Pair<String, Any?>) = OreThemePatch.parse(mapOf("format" to 1, *fields))

    @Test
    fun `defaults are unchanged and missing themes fall back by namespace`() {
        assertEquals(OreColors(), OreThemeCatalog.Default.colors(OreThemeId.Default))
        assertEquals(OreColors(), OreThemeCatalog.Default.colors(OreThemeId("missing", "page")))
        val catalog =
            OreThemeCatalog.create(mapOf(OreThemeId("a") to listOf(patch("colors" to mapOf("panel" to "#123456")))))
        assertEquals(Color(0xFF123456), catalog.colors(OreThemeId("a", "nested/page")).panel)
        assertEquals(OreColors(), catalog.colors(OreThemeId("b", "page")))
    }

    @Test
    fun `global namespace named and pack layers compose without losing omitted fields`() {
        val id = OreThemeId("a", "storage")
        val catalog =
            OreThemeCatalog.create(
                mapOf(
                    OreThemeId.Default to listOf(patch("colors" to mapOf("text" to "#112233"))),
                    OreThemeId("a") to listOf(patch("colors" to mapOf("panel" to "#223344"))),
                    id to
                        listOf(
                            patch("palette" to mapOf("primary" to "#556699")),
                            patch("colors" to mapOf("panel" to "#334455")),
                        ),
                )
            )
        val colors = catalog.colors(id)
        assertEquals(Color(0xFF112233), colors.text)
        assertEquals(Color(0xFF334455), colors.panel)
        assertEquals(Color(0xFF556699), colors.primary)
        assertEquals(OreColors().panel, catalog.colors(OreThemeId("b")).panel)
    }

    @Test
    fun `higher palette resets the whole family then exact overrides win`() {
        val lower =
            patch("colors" to mapOf("primaryHover" to "#FF0000", "trackFilled" to "#00FF00")).applyTo(OreColors())
        val changed =
            patch("palette" to mapOf("primary" to "#3355AA"), "colors" to mapOf("primaryPressed" to "#123456"))
                .applyTo(lower)
        assertNotEquals(lower.primaryHover, changed.primaryHover)
        assertEquals(Color(0xFF3355AA), changed.trackFilled)
        assertEquals(Color(0xFF123456), changed.primaryPressed)
        assertNotEquals(lower.markedSlot, changed.markedSlot)
    }

    @Test
    fun `colors are RGBA and exact values remain exact`() {
        val colors = patch("colors" to mapOf("backdrop" to "#12345678", "text" to "#abcdef")).applyTo(OreColors())
        assertEquals(Color(0x78123456), colors.backdrop)
        assertEquals(Color(0xFFABCDEF), colors.text)
    }

    @Test
    fun `bad files fail as a whole and report the key`() {
        for (fields in
            listOf(
                mapOf("format" to 2),
                mapOf("format" to "1"),
                mapOf("format" to 1, "colours" to emptyMap<String, String>()),
                mapOf("format" to 1, "colors" to mapOf("panel" to "red")),
                mapOf("format" to 1, "colors" to mapOf("panel" to "#112233", "typo" to "#123456")),
                mapOf("format" to 1, "palette" to mapOf("panel" to "#112233")),
                mapOf("format" to 1, "preset" to null),
            )) assertFailsWith<IllegalArgumentException> { OreThemePatch.parse(fields) }
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                    patch("colors" to mapOf("panel" to "#123"))
                }
                .message
                .orEmpty(),
            "colors.panel",
        )
    }

    @Test
    fun `snapshots own their data and removed files restore defaults`() {
        val tokens = mutableMapOf("panel" to "#112233")
        val layer = patch("colors" to tokens)
        tokens["panel"] = "#FFFFFF"
        val layers = mutableMapOf(OreThemeId("a") to mutableListOf(layer))
        val snapshot = OreThemeCatalog.create(layers)
        layers.clear()
        assertEquals(Color(0xFF112233), snapshot.colors(OreThemeId("a")).panel)
        assertEquals(OreColors(), OreThemeCatalog.create(layers).colors(OreThemeId("a")))
        assertEquals(OreThemeCatalog.Default, OreThemeCatalog.create(emptyMap()))
    }

    @Test
    fun `light preset is reusable and permits pack overrides`() {
        val catalog =
            OreThemeCatalog.create(
                mapOf(
                    OreThemeId("a") to
                        listOf(
                            patch("preset" to "light"),
                            patch("colors" to mapOf("panel" to "#EEEEEE")),
                        )
                )
            )
        assertEquals(OrePalettes.Light, OreThemeCatalog.Default.colors(OreThemeId.Light))
        assertEquals(Color(0xFFEEEEEE), catalog.colors(OreThemeId("a", "storage")).panel)
        assertEquals(OrePalettes.Light.primary, catalog.colors(OreThemeId("a")).primary)
        assertReadable(OrePalettes.Light)
    }

    @Test
    fun `twilight is built in as a theme and a preset`() {
        assertEquals(OrePalettes.Twilight, OreThemeCatalog.Default.colors(OreThemeId.Twilight))
        assertEquals(OrePalettes.Twilight, patch("preset" to "twilight").applyTo(OrePalettes.Light))
        val catalog =
            OreThemeCatalog.create(
                mapOf(
                    OreThemeId.Default to listOf(patch("colors" to mapOf("panel" to "#123456"))),
                    OreThemeId.Twilight to listOf(patch("colors" to mapOf("text" to "#FFFFFF"))),
                )
            )
        // Like light, twilight skips the global default but still applies its own theme files.
        assertEquals(OrePalettes.Twilight.panel, catalog.colors(OreThemeId.Twilight).panel)
        assertEquals(Color.White, catalog.colors(OreThemeId.Twilight).text)
        assertReadable(OrePalettes.Twilight)
    }

    private fun assertReadable(colors: OreColors) {
        fun contrast(a: Color, b: Color): Float =
            (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
        for ((foreground, background) in
            listOf(
                colors.text to colors.panel,
                colors.mutedText to colors.raised,
                colors.onPrimary to colors.primary,
                colors.onPrimary to colors.primaryHover,
                colors.onSecondary to colors.secondary,
                colors.onDanger to colors.danger,
            )) {
            assertTrue(contrast(foreground, background) >= 4.5f, "$foreground on $background")
        }
    }

    @Test
    fun `invalid identifiers cannot escape the theme directory`() {
        for (path in listOf("", "../x", "/x", "a//b", "a/./b", "A")) {
            assertFailsWith<IllegalArgumentException> { OreThemeId("a", path) }
        }
        assertFailsWith<IllegalArgumentException> { OreThemeId("Bad Mod") }
    }

    @Test
    fun `live catalog updates preserve remembered state and reach dialogs popups and nested themes`() {
        val id = OreThemeId("a")
        val state = ComposeThread.call { mutableStateOf(OreThemeCatalog.Default) }
        var creations = 0
        var observed = emptyList<OreColors>()
        var dialogColor = Color.Unspecified
        var popupColor = Color.Unspecified
        var draft = ""
        var edit: (String) -> Unit = {}
        UiSession(Viewport(320, 240)) {
                OreThemeResources(state.value) {
                    OreTheme(id) {
                        var text by remember {
                            creations++
                            mutableStateOf("")
                        }
                        val own = OreTheme.colors
                        var other = own
                        OreTheme(OreThemeId("b")) { other = OreTheme.colors }
                        SideEffect {
                            observed = listOf(own, other)
                            draft = text
                            edit = { text = it }
                        }
                        OreDialog("Theme", {}, buttons = {}) {
                            val color = OreTheme.colors.panel
                            SideEffect { dialogColor = color }
                            Box(Modifier.size(12.dp))
                        }
                        Popup {
                            val color = OreTheme.colors.panel
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
                settle()
                ComposeThread.call {
                    edit("unsaved draft")
                    state.value = OreThemeCatalog.create(mapOf(id to listOf(patch("preset" to "light"))))
                }
                settle()
                ComposeThread.call {
                    assertEquals(1, creations)
                    assertEquals("unsaved draft", draft)
                    assertEquals(listOf(OrePalettes.Light, OreColors()), observed)
                    assertEquals(OrePalettes.Light.panel, dialogColor)
                    assertEquals(OrePalettes.Light.panel, popupColor)
                    state.value = OreThemeCatalog.create(mapOf(id to listOf(patch("preset" to "light"))))
                }
                assertNull(session.frame(time + 20_000_000L), "Equal reload must retain the recorded frame")
            }
    }
}
