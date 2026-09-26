package dev.composemc.ui.ore.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalOreColors = staticCompositionLocalOf { OreColors() }
private val LocalOreTypography = staticCompositionLocalOf { OreTypography() }
internal val LocalOreFeedback = staticCompositionLocalOf { OreFeedback {} }
internal val LocalOreContentColor = compositionLocalOf { Color(0xFFF2F3F4) }

object OreTheme {
    val colors: OreColors
        @Composable get() = LocalOreColors.current

    val typography: OreTypography
        @Composable get() = LocalOreTypography.current
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
