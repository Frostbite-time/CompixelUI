package dev.compixel.testing.ui

import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.compixel.host.UiSession
import dev.compixel.platform.ResourceSource
import dev.compixel.platform.Viewport
import dev.compixel.testing.ResourceFontFixture
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.theme.OreTheme
import dev.compixel.ui.ore.theme.OreTypography
import dev.compixel.ui.resources.LocalResources
import dev.compixel.ui.resources.UiResources
import dev.compixel.ui.resources.fontResource
import kotlin.test.*
import org.junit.jupiter.api.Test

class ResourceFontTest {
    @Test
    fun reloadingTheDefaultFontChangesTextAndPopupFontsWithoutLosingStateOrOldFrames() {
        var bytes = ResourceFontFixture.original
        var reads = 0
        val resources =
            UiResources(
                ResourceSource {
                    reads++
                    bytes
                }
            )
        // A consumer can keep its sizing outside composition; null font families still follow resources.
        val typography = OreTypography(body = OreTypography().body.copy(fontSize = 16.sp))
        var font: FontFamily? = null
        var popupFont: FontFamily? = null
        var width = 0
        var creations = 0
        var draft = ""
        var edit: (String) -> Unit = {}
        UiSession(Viewport(320, 100)) {
                CompositionLocalProvider(LocalResources provides resources) {
                    OreTheme(typography = typography) {
                        var text by remember {
                            creations++
                            mutableStateOf("Reload")
                        }
                        val styles = OreTheme.typography
                        val explicit = fontResource(ResourceFontFixture.PATH)
                        val measured = rememberTextMeasurer().measure("Reload", styles.body).size.width
                        SideEffect {
                            assertSame(explicit, styles.body.fontFamily)
                            font = styles.body.fontFamily
                            width = measured
                            draft = text
                            edit = { text = it }
                        }
                        OreText(text, style = typography.body)
                        Popup {
                            val family = OreTheme.typography.body.fontFamily
                            SideEffect { popupFont = family }
                        }
                    }
                }
            }
            .use { session ->
                session.frame(1)!!.use { original ->
                    val originalPixels = original.encodePng()
                    val originalFont = font
                    val originalWidth = width
                    assertTrue(originalWidth > 0)
                    assertEquals(originalFont, popupFont)
                    assertEquals(1, reads)
                    edit("unsaved draft")
                    session.frame(2)?.close()
                    bytes = ResourceFontFixture.smaller
                    resources.reload()
                    session.frame(3)?.close()
                    assertNotEquals(originalFont, font, "Compose must not reuse a typeface under the old font identity")
                    assertEquals(font, popupFont)
                    assertTrue(width in 1 until originalWidth, "$width did not shrink from $originalWidth")
                    assertEquals("unsaved draft", draft)
                    assertEquals(1, creations)
                    assertEquals(2, reads)
                    assertEquals(1, resources.cachedResources)
                    assertContentEquals(originalPixels, original.encodePng(), "Reload changed a retained frame")
                }
            }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun missingAndInvalidFontsUseTheFallbackAndRecoverAfterReload() {
        var bytes: ByteArray? = null
        var reads = 0
        val errors = mutableListOf<Exception>()
        val resources =
            UiResources(
                ResourceSource {
                    reads++
                    bytes
                },
                onError = { _, error -> errors += error },
            )
        var family: FontFamily? = null
        UiSession(Viewport(40, 40)) {
                CompositionLocalProvider(LocalResources provides resources) {
                    val loaded = fontResource("example:font/body.ttf", FontFamily.Monospace)
                    val shared = fontResource("example:font/body.ttf", FontFamily.Monospace)
                    SideEffect {
                        assertSame(loaded, shared)
                        family = loaded
                    }
                }
            }
            .use { session ->
                session.frame(1)?.close()
                assertSame(FontFamily.Monospace, family)
                assertEquals(1, reads)
                assertEquals(1, errors.size)
                bytes = ResourceFontFixture.original
                resources.reload()
                session.frame(2)?.close()
                assertNotEquals(FontFamily.Monospace, family)
                bytes = byteArrayOf(1, 2, 3)
                resources.reload()
                session.frame(3)?.close()
                assertSame(FontFamily.Monospace, family)
                assertEquals(2, errors.size)
                bytes = ResourceFontFixture.smaller
                resources.reload()
                session.frame(4)?.close()
                assertNotEquals(FontFamily.Monospace, family)
                assertEquals(4, reads)
            }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun explicitThemeFontsArePreservedAndDesktopStillHasTheBundledFont() {
        var font: FontFamily? = null
        var custom: FontFamily? = null
        val typography = OreTypography(caption = OreTypography().caption.copy(fontFamily = FontFamily.Monospace))
        UiSession(Viewport(40, 40)) {
                OreTheme(typography = typography) {
                    val styles = OreTheme.typography
                    SideEffect {
                        font = styles.body.fontFamily
                        custom = styles.caption.fontFamily
                    }
                    OreText("A")
                }
            }
            .use { it.frame(1)?.close() }
        assertNotNull(font)
        assertNotEquals(FontFamily.Default, font)
        assertSame(FontFamily.Monospace, custom)
    }
}
