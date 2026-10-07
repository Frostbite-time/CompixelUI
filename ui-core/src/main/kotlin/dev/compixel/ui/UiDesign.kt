package dev.compixel.ui

import androidx.compose.runtime.Composable
import dev.compixel.ui.theme.LocalSchemes
import dev.compixel.ui.theme.SchemeOwner

/**
 * A design system's setup around everything a host shows. Hosts apply their design around their content, inside the
 * providers of [LocalUiFeedback] and [LocalSchemes]. [Decorate] gives the content the colors of the scheme [owner]'s
 * player chose, read with `owner.colors()`. A design that uses another design system's controls gives them their colors
 * from its own, for example a mod's design giving Ore's slots the mod's slot colors.
 */
interface UiDesign {
    /** Whose schemes the design shows, and so the schemes the color editor offers. */
    val owner: SchemeOwner

    @Composable fun Decorate(content: @Composable () -> Unit)

    /**
     * Content in this design that the color editor shows beside the colors, so that changes can be seen as they are
     * made. The host calls it when the editor opens, on its own thread, for example to snapshot game objects.
     */
    fun preview(): @Composable () -> Unit
}
