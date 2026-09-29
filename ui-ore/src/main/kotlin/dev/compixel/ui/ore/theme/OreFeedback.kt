package dev.compixel.ui.ore.theme

/** Adapters may queue native feedback; implementations must be safe from a Compose callback. */
fun interface OreFeedback {
    fun activate()
}
