package dev.composemc.testing.ui

import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OrePixelArt
import org.junit.jupiter.api.Test
import kotlin.test.*

class OreGlyphTest {
    @Test fun `directional families are one drawing turned every way`() {
        for ((right, down, left, up) in listOf(
            listOf(OreGlyph.ArrowRight, OreGlyph.ArrowDown, OreGlyph.ArrowLeft, OreGlyph.ArrowUp),
            listOf(OreGlyph.ChevronRight, OreGlyph.ChevronDown, OreGlyph.ChevronLeft, OreGlyph.ChevronUp),
        )) {
            assertEquals(right.art.rotated(1), down.art, down.name)
            assertEquals(right.art.mirrored(), left.art, left.name)
            assertEquals(left.art.rotated(1), up.art, up.name)
        }
        assertEquals(OreGlyph.EllipsisHorizontal.art.rotated(1), OreGlyph.EllipsisVertical.art)
    }

    @Test fun `glyphs are distinct and keep a one-cell margin`() {
        assertEquals(OreGlyph.entries.size, OreGlyph.entries.map { it.art }.toSet().size)
        for (glyph in OreGlyph.entries) {
            val cells = (0 until 16).flatMap { y -> (0 until 16).map { x -> x to y } }.filter { (x, y) -> glyph.art.isFilled(x, y) }
            assertTrue(cells.isNotEmpty(), glyph.name)
            assertTrue(cells.none { (x, y) -> x == 0 || y == 0 || x == 15 || y == 15 }, "${glyph.name} reaches the edge")
        }
    }

    @Test fun `pixel art validates its grid and round-trips`() {
        assertFailsWith<IllegalArgumentException> { OrePixelArt("#") }
        assertFailsWith<IllegalArgumentException> { OrePixelArt(*Array(16) { "x".repeat(16) }) }
        val art = OrePixelArt(*Array(16) { y -> if (y == 3) "..##............" else "................" })
        assertTrue(art.isFilled(2, 3))
        assertFalse(art.isFilled(1, 3))
        assertTrue(art.rotated(1).isFilled(12, 2))
        assertEquals(art, art.rotated(4))
        assertEquals(art, art.mirrored().mirrored())
        assertEquals(art, OrePixelArt(*art.toString().lines().toTypedArray()))
    }
}
