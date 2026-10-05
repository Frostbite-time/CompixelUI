package dev.compixel.ui

import androidx.compose.runtime.Composable
import dev.compixel.ui.theme.ThemeId

/**
 * A design system's setup around everything a host shows, such as providing its colors for the host's [ThemeId]. Hosts
 * apply their design around their content, inside the providers of [LocalUiFeedback] and
 * [dev.compixel.ui.theme.LocalThemeCatalog]. One design can wrap another, for example a mod's own tokens inside Ore's.
 */
interface UiDesign {
    @Composable fun Decorate(theme: ThemeId, content: @Composable () -> Unit)
}
