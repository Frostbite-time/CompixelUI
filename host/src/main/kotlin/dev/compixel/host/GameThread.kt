package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Runs code on a host's game thread from UI callbacks, such as a button's onClick. */
fun interface GameThread {
    /**
     * Queues [task] behind the content's actions and tasks already waiting, and returns at once. A task queued while
     * Compose handles an input event runs before the event returns, otherwise at the next client tick; one queued by
     * another task waits for the next round. True only means it was queued: false while the host shows no session or 64
     * actions and tasks wait, and the session's end discards those still waiting. The game state the content reads
     * refreshes after the task. Change the game here; requests the server must handle still go through the network or
     * menu sync.
     */
    fun post(task: Runnable): Boolean
}

/** The game thread of the host that shows this content. */
val LocalGameThread = staticCompositionLocalOf<GameThread> { error("LocalGameThread is provided by a CompixelUI host") }

// The content's state binding, for the composable gameState.
internal val LocalGameStates = staticCompositionLocalOf<UiStateBinding<*, *>?> { null }

/**
 * Game state that [read] takes on the game thread while this composition uses it: [initialValue] until the first
 * sample, at the next client tick or after the next input event that runs an action or task, then every [intervalTicks]
 * ticks and after every action or task. Reading the result never runs [read], and its readers recompose when the value
 * changes; each recomposition passes on the latest [read]. Changing [intervalTicks] or [keys], such as the identity of
 * the data shown, replaces the subscription, which starts again from [initialValue]; leaving the composition ends it.
 * State the content needs from its first frame belongs in the host's member `gameState`.
 *
 * [read] only reads the game. Return values and immutable data, never game objects such as entities, menus or item
 * stacks; read what has to stay consistent in one block.
 */
@Composable
fun <T> gameState(initialValue: T, intervalTicks: Int = 1, vararg keys: Any?, read: () -> T): State<T> {
    val states = checkNotNull(LocalGameStates.current) { "gameState is only available in a CompixelUI host's content" }
    val subscription =
        remember(states, intervalTicks, *keys) { states.composedSubscription(initialValue, intervalTicks, read) }
    SideEffect { subscription.read = read }
    return subscription
}
