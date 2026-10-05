package dev.compixel.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Animates a screen's content in when the screen opens and out when it closes. [enter] and [exit] are Compose's
 * transitions, such as `fadeIn() + slideInVertically()`, and may differ. Parts of the content take their own with
 * `Modifier.animateEnterExit`, the scope's `transition` drives effects of your own, and a screen may use several, for
 * example one for its backdrop and one for its window.
 *
 * The screen takes input from its first frame. When it closes for good, it closes at once and the player has control
 * again, while the content plays its exits above the game without input; the session's resources are released when
 * every exit has finished. A screen that another one only covers while its menu stays open, as a recipe viewer does,
 * keeps its content as it was and does not enter again when it shows again. Outside a CompixelUI host it shows the
 * content.
 */
@Composable
fun ScreenTransition(
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val presence = LocalScreenPresence.current
    val state = remember(presence) { presence?.newState() ?: MutableTransitionState(true) }
    if (presence != null) Follow(presence, state)
    AnimatedVisibility(state, modifier, enter, exit, label = "ScreenTransition", content = content)
}

// Registers the transition with its session and reports when it settles, so the host learns that every exit has
// finished without asking the Compose thread.
@Composable
private fun Follow(presence: ScreenPresence, state: MutableTransitionState<Boolean>) {
    DisposableEffect(presence, state) {
        presence.add(state)
        onDispose { presence.remove(state) }
    }
    val idle = state.isIdle
    SideEffect { if (idle) presence.update() }
}

/**
 * A session's visibility, which every [ScreenTransition] in its content follows. Created on the game thread before the
 * content composes; after that Compose thread only, except [animated] and [exited].
 */
internal class ScreenPresence {
    private val transitions = LinkedHashSet<MutableTransitionState<Boolean>>()
    private var visible = true

    /** Whether any [ScreenTransition] is in the content. Read on the game thread. */
    @Volatile
    var animated = false
        private set

    /** Whether every exit has finished since [hide]. Read on the game thread. */
    @Volatile
    var exited = false
        private set

    /** A transition entering now, or staying hidden once the session exits. */
    fun newState() = MutableTransitionState(false).apply { targetState = visible }

    fun add(state: MutableTransitionState<Boolean>) {
        transitions += state
        animated = true
        update()
    }

    fun remove(state: MutableTransitionState<Boolean>) {
        transitions -= state
        animated = transitions.isNotEmpty()
        update()
    }

    /** The host closed for good: every transition plays its exit. */
    fun hide() {
        visible = false
        transitions.forEach { it.targetState = false }
        update()
    }

    /** Recomputes [exited] when a transition may have settled. */
    fun update() {
        exited = !visible && transitions.all { !it.currentState && it.isIdle }
    }
}

internal val LocalScreenPresence = staticCompositionLocalOf<ScreenPresence?> { null }
