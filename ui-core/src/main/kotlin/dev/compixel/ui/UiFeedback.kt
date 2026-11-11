package dev.compixel.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The host's answer when the user activates a control, such as the click sound of a vanilla button. A control reports
 * its activation from its own input handling, for example right after a button's `onClick`, so callers never play
 * anything themselves and every control of a design system sounds alike. Read it from [LocalUiFeedback].
 */
fun interface UiFeedback {
    /**
     * Called when a control was activated. A host may queue the feedback and give it once the input event is handled.
     */
    fun activate()

    companion object {
        /** No feedback, for example outside a host. */
        val None = UiFeedback {}
    }
}

/** The feedback of the host showing this composition. */
val LocalUiFeedback = staticCompositionLocalOf { UiFeedback.None }
