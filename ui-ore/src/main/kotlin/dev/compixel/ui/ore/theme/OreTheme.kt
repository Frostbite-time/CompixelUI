package dev.compixel.ui.ore.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalOreColors = staticCompositionLocalOf { OreColors() }
private val LocalOreTypography = staticCompositionLocalOf { OreTypography() }
private val LocalOreThemeCatalog = compositionLocalOf { OreThemeCatalog.Default }
internal val LocalOreFeedback = staticCompositionLocalOf { OreFeedback {} }
internal val LocalOreContentColor = compositionLocalOf { Color(0xFFF2F3F4) }

object OreTheme {
    val colors: OreColors
        @Composable get() = LocalOreColors.current

    val typography: OreTypography
        @Composable get() = LocalOreTypography.current
}

/** Supplies a resource snapshot to this tree, including nested themes and Compose overlays. */
@Composable
fun OreThemeResources(catalog: OreThemeCatalog, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOreThemeCatalog provides catalog, content = content)
}

/** Resolves a named theme from the current resource snapshot without replacing the composition. */
@Composable
fun OreTheme(
    id: OreThemeId,
    typography: OreTypography = OreTheme.typography,
    feedback: OreFeedback = LocalOreFeedback.current,
    content: @Composable () -> Unit,
) {
    OreTheme(LocalOreThemeCatalog.current.colors(id), typography, feedback, content)
}

@Composable
fun OreTheme(
    colors: OreColors = OreTheme.colors,
    typography: OreTypography = OreTheme.typography,
    feedback: OreFeedback = LocalOreFeedback.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOreColors provides colors,
        LocalOreTypography provides typography,
        LocalOreContentColor provides colors.text,
        LocalOreFeedback provides feedback,
        content = content,
    )
}
