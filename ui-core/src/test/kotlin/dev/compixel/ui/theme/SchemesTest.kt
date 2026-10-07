package dev.compixel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.test.*
import org.junit.jupiter.api.Test

class SchemesTest {
    private object Colors : ColorSchema("test") {
        val panel = color("panel", "surfaces", Color(0xFF202020))
        val accent = color("accent", "accent", Color(0xFF3C8527))
        val hover =
            derived("hover", "accent", accent, default = Color(0xFF2F6B20)) { lerp(it[accent], Color.Black, .5f) }
        val pressed = derived("pressed", "accent", hover) { lerp(it[hover], Color.Black, .5f) }

        override val builtIn =
            listOf(
                BuiltInScheme("default", "test.default"),
                BuiltInScheme("light", "test.light", mapOf(panel to Color.White, hover to Color(0xFF00FF00))),
            )
    }

    private object Plain : ColorSchema("plain") {
        val panel = color("panel", "surfaces", Color(0xFF101010))
    }

    private val owner = SchemeOwner("mod", Colors)

    private fun file(vararg fields: Pair<String, Any?>, source: String = "pack") =
        SchemeFile(source, mapOf("format" to 1, *fields))

    private fun colors(vararg colors: Pair<String, String>) = "colors" to mapOf(*colors)

    private fun catalog(
        schemes: Map<String, List<SchemeFile>> = emptyMap(),
        index: List<SchemeFile> = emptyList(),
        warn: (String) -> Unit = {},
    ) = SchemeCatalog.create(schemes.mapKeys { SchemeId("mod", it.key) }, mapOf("mod" to index), warn)

    @Test
    fun `derived colors keep their default until something changes what they follow`() {
        assertEquals(Color(0xFF2F6B20), Colors.defaults[Colors.hover])
        assertFalse(Colors.defaults.follows(Colors.hover))
        // The pressed color has no default, so it follows the hover color's own default.
        assertEquals(lerp(Color(0xFF2F6B20), Color.Black, .5f), Colors.defaults[Colors.pressed])
        val blue = Colors.values(Colors.accent to Color.Blue)
        assertEquals(lerp(Color.Blue, Color.Black, .5f), blue[Colors.hover])
        assertTrue(blue.follows(Colors.hover) && blue.follows(Colors.pressed))
        assertEquals(lerp(blue[Colors.hover], Color.Black, .5f), blue[Colors.pressed])
        // Setting both in one layer keeps both exactly.
        val exact = Colors.values(Colors.accent to Color.Blue, Colors.hover to Color.Red)
        assertEquals(Color.Red, exact[Colors.hover])
        assertFalse(exact.follows(Colors.hover))
    }

    @Test
    fun `a derived color set in a lower layer follows again when a higher layer changes what it follows`() {
        val catalog =
            catalog(
                mapOf(
                    "light" to
                        listOf(file(colors("panel" to "#EEEEEE")), file(colors("accent" to "#0000FF"), source = "top"))
                )
            )
        val light = Schemes(catalog, SchemeSettings()).colors(owner, "light")
        assertEquals(Color(0xFFEEEEEE), light[Colors.panel])
        // The built-in light scheme sets hover, but the top pack changed the accent it follows.
        assertEquals(lerp(Color.Blue, Color.Black, .5f), light[Colors.hover])
        val untouched = Schemes(catalog(), SchemeSettings()).colors(owner, "light")
        assertEquals(Color(0xFF00FF00), untouched[Colors.hover])
        assertEquals(Color.White, untouched[Colors.panel])
    }

    @Test
    fun `schemes list the index order then built-in schemes then files and choose the default`() {
        val catalog =
            catalog(
                mapOf("zinc" to listOf(file()), "amber" to listOf(file()), "light" to listOf(file())),
                listOf(file("order" to listOf("zinc", "missing")), file("default" to "amber", source = "top")),
            )
        assertEquals(listOf("zinc", "default", "light", "amber"), catalog.paths(owner))
        assertEquals("amber", catalog.default(owner))
        assertEquals("default", catalog().default(owner))
        val schemes = Schemes(catalog, SchemeSettings().with(owner, SchemeSettings.Choice(scheme = "zinc")))
        assertEquals("zinc", schemes.selected(owner))
        val gone = Schemes(catalog(), SchemeSettings().with(owner, SchemeSettings.Choice(scheme = "zinc")))
        assertEquals("default", gone.selected(owner))
        assertEquals("test.light", catalog.name(owner, "light"))
        // A schema without built-in schemes or files still offers the owner's default, with the schema's colors.
        val plain = SchemeOwner("plain", Plain, default = "standard")
        assertEquals(listOf("standard"), catalog.paths(plain))
        assertEquals(Plain.defaults, Schemes(catalog, SchemeSettings()).colors(plain))
    }

    @Test
    fun `files stack in pack order and a scheme extends another`() {
        val catalog =
            catalog(
                mapOf(
                    "base" to listOf(file(colors("panel" to "#111111", "accent" to "#222222"))),
                    "child" to
                        listOf(
                            file("extends" to "base", "name" to "Child", colors("panel" to "#333333")),
                            file(colors("panel" to "#444444"), source = "top"),
                        ),
                )
            )
        val child = Schemes(catalog, SchemeSettings()).colors(owner, "child")
        assertEquals(Color(0xFF444444), child[Colors.panel])
        assertEquals(Color(0xFF222222), child[Colors.accent])
        assertEquals("Child", catalog.name(owner, "child"))
    }

    @Test
    fun `problems skip only what they concern and are reported once`() {
        val warnings = mutableListOf<String>()
        val catalog =
            catalog(
                mapOf(
                    "broken" to
                        listOf(
                            file(colors("panel" to "#123456", "typo" to "#FFFFFF", "accent" to "blue"), "extra" to 1),
                            SchemeFile("old", mapOf("format" to 2, "colors" to mapOf("panel" to "#000000"))),
                        ),
                    "loop" to listOf(file("extends" to "loop")),
                ),
                warn = warnings::add,
            )
        val schemes = Schemes(catalog, SchemeSettings())
        val broken = schemes.colors(owner, "broken")
        assertEquals(Color(0xFF123456), broken[Colors.panel])
        assertEquals(Colors.defaults[Colors.accent], broken[Colors.accent])
        schemes.colors(owner, "loop")
        Schemes(catalog, SchemeSettings()).colors(owner, "broken")
        assertEquals(5, warnings.size, warnings.joinToString("\n"))
        assertTrue(warnings.any { "typo" in it } && warnings.any { "extra" in it } && warnings.any { "old" in it })
        assertTrue(warnings.any { "accent" in it } && warnings.any { "extends itself" in it })
    }

    @Test
    fun `the player's colors go on top and their settings survive a round trip`() {
        val settings =
            SchemeSettings()
                .with(
                    owner,
                    SchemeSettings.Choice(
                        scheme = "light",
                        edits = mapOf("light" to mapOf("accent" to Color(0x80FF0000), "removed" to Color.Black)),
                    ),
                )
        val schemes = Schemes(catalog(), settings)
        val light = schemes.colors(owner)
        assertEquals(Color(0x80FF0000), light[Colors.accent])
        assertTrue(light.follows(Colors.hover))
        assertEquals(mapOf(Colors.accent to Color(0x80FF0000)), schemes.edits(owner, "light"))
        val warnings = mutableListOf<String>()
        assertEquals(settings, SchemeSettings.decode(settings.encode(), warnings::add))
        assertTrue(warnings.isEmpty())
        assertEquals(SchemeSettings(), SchemeSettings.decode(mapOf("format" to 9), warnings::add))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `export writes the player's colors as an override or as a new scheme`() {
        val settings =
            SchemeSettings().with(owner, SchemeSettings.Choice(edits = mapOf("light" to mapOf("accent" to Color.Red))))
        val schemes = Schemes(catalog(), settings)
        assertEquals(
            mapOf(
                "assets/mod/compixel/schemes/light.json" to
                    mapOf("format" to 1, "colors" to mapOf("accent" to "#FF0000"))
            ),
            schemes.export(owner, "light"),
        )
        val created = schemes.export(owner, "light", newPath = "pack", name = "Pack colors", makeDefault = true)
        assertEquals(
            mapOf(
                "format" to 1,
                "name" to "Pack colors",
                "extends" to "light",
                "colors" to mapOf("accent" to "#FF0000"),
            ),
            created["assets/mod/compixel/schemes/pack.json"],
        )
        assertEquals(mapOf("format" to 1, "default" to "pack"), created["assets/mod/compixel/schemes.json"])
        // An exported scheme reads back as the colors the player saw.
        val pack = created.map { (path, document) -> path to SchemeFile("export", document) }.toMap()
        val installed =
            SchemeCatalog.create(
                mapOf(SchemeId("mod", "pack") to listOf(pack.getValue("assets/mod/compixel/schemes/pack.json"))),
                mapOf("mod" to listOf(pack.getValue("assets/mod/compixel/schemes.json"))),
            )
        val shared = Schemes(installed, SchemeSettings())
        assertEquals("pack", shared.selected(owner))
        assertEquals(schemes.colors(owner, "light"), shared.colors(owner))
    }

    @Test
    fun `colors round trip through hex and catalogs keep their own copy`() {
        for (hex in listOf("#12345678", "#ABCDEF")) assertEquals(
            hex,
            ColorHex.format(assertNotNull(ColorHex.parse(hex))),
        )
        assertNull(ColorHex.parse("#123"))
        val colors = mutableMapOf("panel" to "#123456")
        val catalog = catalog(mapOf("default" to listOf(file("colors" to colors))))
        colors["panel"] = "#FFFFFF"
        assertEquals(Color(0xFF123456), Schemes(catalog, SchemeSettings()).colors(owner, "default")[Colors.panel])
    }

    @Test
    fun `identifiers cannot escape the scheme directory`() {
        for (path in listOf("", "../x", "/x", "a//b", "a/./b", "A")) {
            assertFailsWith<IllegalArgumentException> { SchemeId("a", path) }
        }
        assertFailsWith<IllegalArgumentException> { SchemeOwner("Bad Mod", Colors) }
    }
}
