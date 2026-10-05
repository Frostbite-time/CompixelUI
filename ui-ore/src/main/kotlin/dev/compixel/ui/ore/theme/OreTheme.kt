package dev.compixel.ui.ore.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.compixel.ui.UiDesign
import dev.compixel.ui.theme.ThemeId
import dev.compixel.ui.theme.ThemeSection
import dev.compixel.ui.theme.current

private val LocalOreColors = staticCompositionLocalOf { OreColors() }
private val LocalOreTypography = staticCompositionLocalOf { OreTypography() }
internal val LocalOreContentColor = compositionLocalOf { Color(0xFFF2F3F4) }

object OreTheme {
    val colors: OreColors
        @Composable get() = LocalOreColors.current

    val typography: OreTypography
        @Composable get() = LocalOreTypography.current
}

/**
 * Ore's colors in theme files, under `"ore"`: a `preset`, generated `palette` families and exact `colors` roles. The
 * built-in [ThemeId.Light] and [ThemeId.Twilight] themes start from Ore's light and twilight palettes.
 */
object OreThemeSection : ThemeSection<OreColors>("ore") {
    override val default = OreColors()

    override fun builtIn(id: ThemeId): OreColors? =
        when (id) {
            ThemeId.Light -> OrePalettes.Light
            ThemeId.Twilight -> OrePalettes.Twilight
            else -> null
        }

    override fun apply(value: OreColors, layer: Any?): OreColors {
        require(layer is Map<*, *>) { "expected an object" }
        return OreThemePatch.parse(layer).applyTo(value)
    }
}

/** Ore as a host's design system: the content gets the Ore theme of the host's [ThemeId]. */
object OreDesign : UiDesign {
    @Composable
    override fun Decorate(theme: ThemeId, content: @Composable () -> Unit) {
        OreTheme(theme, content = content)
    }
}

/** Resolves a named theme from the current theme files without replacing the composition. */
@Composable
fun OreTheme(id: ThemeId, typography: OreTypography = OreTheme.typography, content: @Composable () -> Unit) {
    OreTheme(OreThemeSection.current(id), typography, content)
}

@Composable
fun OreTheme(
    colors: OreColors = OreTheme.colors,
    typography: OreTypography = OreTheme.typography,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOreColors provides colors,
        LocalOreTypography provides typography,
        LocalOreContentColor provides colors.text,
        content = content,
    )
}
