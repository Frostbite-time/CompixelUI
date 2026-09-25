package dev.composemc.demo.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.display.OrePixelArt
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.layout.OreSurfaceStyle
import dev.composemc.ui.ore.theme.OreTheme

/** A domain icon kept by its consumer rather than the library, in Ore's pixel style. */
internal val DemoNetworkArt = OrePixelArt(
    "................",
    "......####......",
    "......####......",
    "......####......",
    "......####......",
    ".......##.......",
    ".......##.......",
    ".......##.......",
    "..############..",
    "..############..",
    "..##........##..",
    ".####......####.",
    ".####......####.",
    ".####......####.",
    ".####......####.",
    "................",
)

private class GlyphFamily(val english: String, val chinese: String, val glyphs: List<OreGlyph>)

private val glyphFamilies = listOf(
    GlyphFamily("Arrows", "箭头", listOf(OreGlyph.ArrowUp, OreGlyph.ArrowRight, OreGlyph.ArrowDown, OreGlyph.ArrowLeft)),
    GlyphFamily("Chevrons", "折角", listOf(OreGlyph.ChevronUp, OreGlyph.ChevronRight, OreGlyph.ChevronDown, OreGlyph.ChevronLeft)),
    GlyphFamily("Marks", "符号", listOf(OreGlyph.Plus, OreGlyph.Minus, OreGlyph.Cross, OreGlyph.Checkmark)),
    GlyphFamily("Menus", "菜单", listOf(OreGlyph.Bars, OreGlyph.EllipsisHorizontal, OreGlyph.EllipsisVertical)),
    GlyphFamily("Status", "状态", listOf(OreGlyph.CheckmarkCircle, OreGlyph.InformationCircle,
        OreGlyph.ExclamationCircle, OreGlyph.ExclamationTriangle)),
    GlyphFamily("Tools", "工具", listOf(OreGlyph.MagnifyingGlass, OreGlyph.Pencil, OreGlyph.Trash, OreGlyph.Gear,
        OreGlyph.Sliders, OreGlyph.Funnel, OreGlyph.CycleArrows)),
)

/** Every built-in glyph by family, at two sizes, plus colors, scaling and a consumer-defined icon. */
@Composable
internal fun IconsPage(label: (String, String) -> String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OreText(label("Icons", "图标"), style = OreTheme.typography.title)
        for (family in glyphFamilies) {
            OreText(label(family.english, family.chinese), color = OreTheme.colors.mutedText)
            IconSamples(family.glyphs.map { it.art to it.name })
        }
        OreText(label("Sizes and colors", "尺寸与颜色"), color = OreTheme.colors.mutedText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (size in listOf(8, 12, 16, 24)) OreIcon(OreGlyph.Gear, Modifier.size(size.dp))
            for (color in listOf(OreTheme.colors.primary, OreTheme.colors.danger, OreTheme.colors.mutedText)) {
                OreIcon(OreGlyph.Gear, Modifier.size(16.dp), color)
            }
        }
        OreText(label("Consumer pixel art", "消费者自定义图标"), color = OreTheme.colors.mutedText)
        IconSamples(listOf(DemoNetworkArt to "OrePixelArt"))
    }
}

@Composable
private fun IconSamples(samples: List<Pair<OrePixelArt, String>>) {
    samples.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((art, name) in row) {
                OreSurface(Modifier.weight(1f), style = OreSurfaceStyle.Inset) {
                    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OreIcon(art, Modifier.size(16.dp))
                        OreIcon(art)
                        OreText(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
