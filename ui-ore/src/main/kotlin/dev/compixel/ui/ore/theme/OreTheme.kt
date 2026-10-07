package dev.compixel.ui.ore.theme

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.overlay.OreTextContextMenu
import dev.compixel.ui.theme.ColorValues
import dev.compixel.ui.theme.SchemeOwner
import dev.compixel.ui.theme.colors

private val LocalOreColors = staticCompositionLocalOf { OreColors.defaults }
private val LocalOreTypography = staticCompositionLocalOf { OreTypography() }
internal val LocalOreContentColor = compositionLocalOf { Color(0xFFF2F3F4) }

object OreTheme {
    /** The Ore colors of the surrounding [OreTheme], read with `colors[OreColors.panel]`. */
    val colors: ColorValues
        @Composable get() = LocalOreColors.current

    val typography: OreTypography
        @Composable get() = LocalOreTypography.current
}

/**
 * Gives [content] Ore's [colors] and [typography]: its controls, text selection and text-field menus. A mod's own
 * design can pass Ore colors made from its own, as `OreColors.values(OreColors.slot to modColors[ModColors.slot],
 * ...)`.
 */
@Composable
fun OreTheme(
    colors: ColorValues = OreTheme.colors,
    typography: OreTypography = OreTheme.typography,
    content: @Composable () -> Unit,
) {
    require(colors.schema === OreColors) { "OreTheme needs Ore colors, not ${colors.schema}" }
    val selection = remember(colors) { TextSelectionColors(colors[OreColors.primary], colors[OreColors.selection]) }
    CompositionLocalProvider(
        LocalOreColors provides colors,
        LocalOreTypography provides typography,
        LocalOreContentColor provides colors[OreColors.text],
        LocalTextSelectionColors provides selection,
        LocalContextMenuRepresentation provides OreTextContextMenu,
        content = content,
    )
}

/**
 * Ore as a host's design: its content gets the Ore scheme that [owner]'s player chose. Screens use CompixelUI's own
 * schemes by default; a mod that styles its screens with Ore can name itself, `OreDesign("examplemod")`, to offer
 * schemes of its own.
 */
class OreDesign(namespace: String = "compixel") : UiDesign {
    override val owner = SchemeOwner(namespace, OreColors)

    @Composable
    override fun Decorate(content: @Composable () -> Unit) {
        OreTheme(owner.colors(), content = content)
    }

    override fun preview(): @Composable () -> Unit = { OrePreview() }
}
