package dev.compixel.ui.theme

import kotlin.test.*
import org.junit.jupiter.api.Test

class ThemeCatalogTest {
    /** A section of named string tokens; each file sets some of them. */
    private object Tokens : ThemeSection<Map<String, String>>("tokens") {
        var applied = 0

        override val default = mapOf("panel" to "default", "text" to "default")

        override fun builtIn(id: ThemeId) =
            if (id == ThemeId.Light) mapOf("panel" to "light", "text" to "light") else null

        override fun apply(value: Map<String, String>, layer: Any?): Map<String, String> {
            applied++
            require(layer is Map<*, *>) { "expected an object" }
            return value +
                layer.entries.associate { (key, token) ->
                    require(key is String && key in default) { "$key: unknown token" }
                    require(token is String) { "$key: expected a string" }
                    key to token
                }
        }
    }

    private object Other : ThemeSection<String>("other") {
        override val default = "none"

        override fun apply(value: String, layer: Any?): String = layer as? String ?: error("expected a string")
    }

    private fun file(vararg sections: Pair<String, Any?>, format: Any? = 1) =
        ThemeLayer("test", mapOf("format" to format, *sections))

    private fun tokens(vararg values: Pair<String, String>) = "tokens" to mapOf(*values)

    @Test
    fun `themes without files have the default and inherit by namespace`() {
        assertEquals(Tokens.default, ThemeCatalog.Empty[ThemeId.Default, Tokens])
        assertEquals(Tokens.default, ThemeCatalog.Empty[ThemeId("missing", "page"), Tokens])
        val catalog = ThemeCatalog.create(mapOf(ThemeId("a") to listOf(file(tokens("panel" to "a")))))
        assertEquals("a", catalog[ThemeId("a", "nested/page"), Tokens].getValue("panel"))
        assertEquals(Tokens.default, catalog[ThemeId("b", "page"), Tokens])
    }

    @Test
    fun `global namespace named and pack layers compose without losing omitted values`() {
        val id = ThemeId("a", "storage")
        val catalog =
            ThemeCatalog.create(
                mapOf(
                    ThemeId.Default to listOf(file(tokens("text" to "global"))),
                    ThemeId("a") to listOf(file(tokens("panel" to "namespace"))),
                    id to listOf(file(tokens("panel" to "lower")), file(tokens("panel" to "higher"))),
                )
            )
        assertEquals(mapOf("panel" to "higher", "text" to "global"), catalog[id, Tokens])
        assertEquals(mapOf("panel" to "namespace", "text" to "global"), catalog[ThemeId("a"), Tokens])
        assertEquals(mapOf("panel" to "default", "text" to "global"), catalog[ThemeId("b"), Tokens])
    }

    @Test
    fun `built-in starts skip the global default but apply their own files`() {
        val catalog =
            ThemeCatalog.create(
                mapOf(
                    ThemeId.Default to listOf(file(tokens("panel" to "global"))),
                    ThemeId.Light to listOf(file(tokens("text" to "pack"))),
                )
            )
        assertEquals(mapOf("panel" to "light", "text" to "pack"), catalog[ThemeId.Light, Tokens])
        // A section without a built-in light value starts it from the global default.
        assertEquals("none", catalog[ThemeId.Light, Other])
    }

    @Test
    fun `an invalid section is ignored for that section only and reported once`() {
        val warnings = mutableListOf<String>()
        val id = ThemeId("a")
        val catalog =
            ThemeCatalog.create(
                mapOf(id to listOf(file(tokens("panel" to "kept")), file(tokens("typo" to "x"), "other" to "applied"))),
                warnings::add,
            )
        repeat(2) {
            assertEquals("kept", catalog[id, Tokens].getValue("panel"))
            assertEquals("applied", catalog[id, Other])
        }
        assertEquals(1, warnings.size)
        assertContains(warnings.single(), "tokens section of theme a:default from test: typo: unknown token")
    }

    @Test
    fun `files of another format are left out entirely`() {
        val warnings = mutableListOf<String>()
        val id = ThemeId("a")
        val catalog =
            ThemeCatalog.create(
                mapOf(
                    id to
                        listOf(
                            file(tokens("panel" to "one")),
                            file(tokens("panel" to "two"), format = 2),
                            file(tokens("panel" to "text"), format = "1"),
                            file(tokens("panel" to "none"), format = null),
                        )
                ),
                warnings::add,
            )
        assertEquals("one", catalog[id, Tokens].getValue("panel"))
        assertEquals(3, warnings.size)
        assertTrue(warnings.all { "format: expected 1" in it })
    }

    @Test
    fun `sections that nothing reads are ignored`() {
        val catalog = ThemeCatalog.create(mapOf(ThemeId("a") to listOf(file("unknown" to listOf(1, 2), tokens()))))
        assertEquals(Tokens.default, catalog[ThemeId("a"), Tokens])
    }

    @Test
    fun `each theme resolves its files once`() {
        val id = ThemeId("a", "page")
        val catalog =
            ThemeCatalog.create(
                mapOf(ThemeId("a") to listOf(file(tokens("panel" to "a"))), id to listOf(file(tokens("text" to "b"))))
            )
        val before = Tokens.applied
        repeat(3) { catalog[id, Tokens] }
        catalog[ThemeId("a"), Tokens]
        assertEquals(2, Tokens.applied - before)
    }

    @Test
    fun `catalogs own their files and compare by content`() {
        val values = mutableMapOf("panel" to "first")
        val files = mutableMapOf(ThemeId("a") to mutableListOf(file("tokens" to values)))
        val catalog = ThemeCatalog.create(files)
        values["panel"] = "changed"
        files.clear()
        assertEquals("first", catalog[ThemeId("a"), Tokens].getValue("panel"))
        assertEquals(ThemeCatalog.Empty, ThemeCatalog.create(files))
        assertEquals(
            ThemeCatalog.create(mapOf(ThemeId("a") to listOf(file(tokens("panel" to "x"))))),
            ThemeCatalog.create(mapOf(ThemeId("a") to listOf(file(tokens("panel" to "x"))))),
        )
    }

    @Test
    fun `invalid identifiers cannot escape the theme directory`() {
        for (path in listOf("", "../x", "/x", "a//b", "a/./b", "A")) {
            assertFailsWith<IllegalArgumentException> { ThemeId("a", path) }
        }
        assertFailsWith<IllegalArgumentException> { ThemeId("Bad Mod") }
        assertFailsWith<IllegalArgumentException> {
            object : ThemeSection<String>("format") {
                override val default = ""

                override fun apply(value: String, layer: Any?) = value
            }
        }
    }
}
