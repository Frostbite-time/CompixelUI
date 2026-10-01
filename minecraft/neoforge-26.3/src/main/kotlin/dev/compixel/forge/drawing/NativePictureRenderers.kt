package dev.compixel.forge.drawing

import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration

/** Reuses vanilla and mod registrations, never the main GUI's mutable renderer instances. */
internal object NativePictureRenderers {
    private var registrations: List<PictureInPictureRendererRegistration<*>>? = null

    @Suppress("UNCHECKED_CAST")
    fun registered(event: RegisterPictureInPictureRenderersEvent) {
        // The AT exposes the event's shared list. Later mod listeners may append to it before startup completes.
        // ModDev applies source ATs to Minecraft, but this event comes from the loader's compile dependency.
        // A public-field lookup uses the same runtime AT without suppressing Kotlin access checks.
        registrations =
            event.javaClass.getField("renderers").get(event) as List<PictureInPictureRendererRegistration<*>>
    }

    fun factories(): List<PictureInPictureRendererRegistration<*>> =
        checkNotNull(registrations) { "Native GUI renderer registration has not completed" }.toList()
}
