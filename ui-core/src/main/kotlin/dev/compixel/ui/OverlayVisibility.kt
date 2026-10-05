package dev.compixel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How visible the screen is, for content that draws in a popup or dialog layer of its own: modifiers on the screen's
 * content, such as its enter and exit animation, do not reach such layers. A design system applies it to the overlays
 * it draws, for example as their alpha and the alpha of a dialog's scrim. Read it from [LocalOverlayVisibility].
 */
interface OverlayVisibility {
    /**
     * The screen's visibility from 0 to 1, animated with its entrance and exit. Call it in the overlay's composition;
     * the screen's exit then waits until the overlay has faded too. An overlay that opens while the screen shows starts
     * at 1, without an entrance.
     */
    @Composable fun animate(): State<Float>
}

/** The visibility that a host's screen transition gives the overlays inside it; null outside one. */
val LocalOverlayVisibility = staticCompositionLocalOf<OverlayVisibility?> { null }
