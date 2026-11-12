package dev.compixel.forge.render

import dev.compixel.render.gl.OpenGlSkia

/**
 * Skia's context in the game's GL context, which every screen and HUD layer shares. It stays for the game's lifetime
 * and ends with the GL context.
 */
internal object SkiaContexts {
    /** Skia contexts that exist now; one once any layer has drawn. */
    val live
        get() = OpenGlSkia.liveContexts

    /** Releases the images closed layers left behind, once nothing refers to them. Called every client tick. */
    fun collect() {
        OpenGlSkia.sharedOrNull()?.collect()
    }
}
