package dev.composemc.ui.ore.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.theme.LocalOreContentColor
import kotlin.math.roundToInt

/**
 * A single-color pixel icon on Ore's 16x16 grid. Ore's glyphs use two-cell strokes; define domain icons the same way,
 * once, and draw them with [OreIcon]. Original geometry only: no Minecraft or Bedrock assets are redistributed.
 */
@Immutable
class OrePixelArt private constructor(private val cells: BooleanArray) {
    /** 16 rows of 16 characters, top to bottom: `#` fills a cell and `.` leaves it empty. */
    constructor(vararg rows: String) : this(parse(rows))

    /** Filled cells merged into rectangles, four values each: x, y, width and height in cells. */
    internal val rects: IntArray = merge(cells)

    fun isFilled(x: Int, y: Int): Boolean {
        require(x in 0 until SIZE && y in 0 until SIZE) { "Cell ($x, $y) is outside the 16x16 grid" }
        return cells[y * SIZE + x]
    }

    /** The art flipped left to right. */
    fun mirrored(): OrePixelArt = OrePixelArt(BooleanArray(CELLS) { cells[it - it % SIZE + SIZE - 1 - it % SIZE] })

    /** The art turned clockwise by [quarterTurns] right angles. */
    fun rotated(quarterTurns: Int = 1): OrePixelArt {
        var turned = cells
        repeat(quarterTurns.mod(4)) {
            val source = turned
            turned = BooleanArray(CELLS) { source[(SIZE - 1 - it % SIZE) * SIZE + it / SIZE] }
        }
        return OrePixelArt(turned)
    }

    override fun equals(other: Any?) = other is OrePixelArt && cells.contentEquals(other.cells)

    override fun hashCode() = cells.contentHashCode()

    override fun toString() =
        (0 until SIZE).joinToString("\n") { y ->
            String(CharArray(SIZE) { x -> if (cells[y * SIZE + x]) '#' else '.' })
        }

    private companion object {
        const val SIZE = 16
        const val CELLS = SIZE * SIZE

        fun parse(rows: Array<out String>): BooleanArray {
            require(rows.size == SIZE && rows.all { it.length == SIZE }) { "Pixel art needs 16 rows of 16 characters" }
            return BooleanArray(CELLS) { index ->
                when (val cell = rows[index / SIZE][index % SIZE]) {
                    '#' -> true
                    '.' -> false
                    else -> throw IllegalArgumentException("Pixel art uses '#' and '.', not '$cell'")
                }
            }
        }

        /** Row runs, extended downward while the next row repeats the same run. */
        fun merge(cells: BooleanArray): IntArray {
            val rects = ArrayList<IntArray>()
            var open = emptyList<IntArray>()
            for (y in 0 until SIZE) {
                val next = ArrayList<IntArray>()
                var x = 0
                while (x < SIZE) {
                    if (!cells[y * SIZE + x]) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < SIZE && cells[y * SIZE + x]) x++
                    val above = open.firstOrNull { it[0] == start && it[2] == x - start }
                    if (above != null) {
                        above[3]++
                        next += above
                    } else
                        intArrayOf(start, y, x - start, 1).also {
                            rects += it
                            next += it
                        }
                }
                open = next
            }
            return IntArray(rects.size * 4) { rects[it / 4][it % 4] }
        }
    }
}

/** Draws [art] in [color], 8dp square unless [modifier] sets a size. */
@Composable
fun OreIcon(art: OrePixelArt, modifier: Modifier = Modifier, color: Color = LocalOreContentColor.current) {
    Canvas(modifier.size(8.dp)) {
        val rects = art.rects
        for (i in rects.indices step 4) {
            // Each edge snaps to a device pixel, so strokes keep equal widths at every size.
            val x1 = (rects[i] * size.width / 16).roundToInt().toFloat()
            val y1 = (rects[i + 1] * size.height / 16).roundToInt().toFloat()
            val x2 = ((rects[i] + rects[i + 2]) * size.width / 16).roundToInt().toFloat()
            val y2 = ((rects[i + 1] + rects[i + 3]) * size.height / 16).roundToInt().toFloat()
            drawRect(color, Offset(x1, y1), Size(x2 - x1, y2 - y1))
        }
    }
}

@Composable
fun OreIcon(glyph: OreGlyph, modifier: Modifier = Modifier, color: Color = LocalOreContentColor.current) =
    OreIcon(glyph.art, modifier, color)
