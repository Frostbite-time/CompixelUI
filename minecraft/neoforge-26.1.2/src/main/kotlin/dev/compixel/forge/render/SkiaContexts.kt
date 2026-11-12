package dev.compixel.forge.render

import dev.compixel.render.gl.OpenGlSkia

/** Skia's context in the game's GL context, which every screen and HUD layer shares. */
internal object SkiaContexts {
    /** Skia contexts that exist now; one once any layer has drawn. */
    val live
        get() = OpenGlSkia.liveContexts

    /** Releases the images closed layers left behind, once nothing refers to them. Called every client tick. */
    fun collect() {
        OpenGlSkia.sharedOrNull()?.collect()
    }

    /** Closes the shared context while the game stops, once its last layer has closed. */
    fun closeUnused() {
        OpenGlSkia.sharedOrNull()?.takeIf { it.renderers == 0 }?.shutDown()
    }
}
