package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/** Runs code on a host's game thread from UI callbacks, such as a button's onClick. */
fun interface GameThread {
    /**
     * Queues [task] behind the content's tasks already waiting, and returns at once. A task queued while Compose
     * handles an input event runs before the event returns, otherwise at the next client tick; one queued by another
     * task waits for the next round. True only means it was queued: false while the host shows no session or 64 tasks
     * wait, and the session's end discards those still waiting. The game state the content reads refreshes after the
     * task. Change the game here; requests the server must handle still go through the network or menu sync.
     */
    fun post(task: Runnable): Boolean
}

/** The game thread of the host that shows this content. */
val LocalGameThread = staticCompositionLocalOf<GameThread> { error("LocalGameThread is provided by a CompixelUI host") }

// The content's state binding.
internal val LocalGameStates = staticCompositionLocalOf<UiStateBinding?> { null }

/**
 * Game state that [read] takes on the game thread while this composition uses it: immediately on the first call, then
 * every [intervalTicks] client ticks and after tasks posted through [GameThread]. Reading the result never runs [read],
 * and its readers recompose when the value changes; each recomposition passes on the latest [read]. Changing
 * [intervalTicks] or [keys], such as the identity of the data shown, replaces the subscription and immediately reads
 * its new value; leaving the composition ends it. Closing the host stops reads and keeps the last value for its exit.
 *
 * [read] only reads the game. Return values and immutable data, never game objects such as entities, menus or item
 * stacks; read what has to stay consistent in one block.
 */
@Composable
fun <T> gameState(intervalTicks: Int = 1, vararg keys: Any?, read: () -> T): State<T> {
    val states = checkNotNull(LocalGameStates.current) { "gameState is only available in a CompixelUI host's content" }
    return states.gameState(intervalTicks, *keys, read = read)
}
