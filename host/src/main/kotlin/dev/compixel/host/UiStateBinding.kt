package dev.compixel.host

import androidx.compose.runtime.Composable

/**
 * Game state for a host's content, renewed with each of the host's UI sessions. [open] takes the first [snapshot]
 * before the session composes. [handleActions] runs the actions the content sent through [handle], as a host does after
 * each input event, and publishes a new snapshot when one ran; each [tick] runs the pending actions and then publishes
 * a snapshot. [close] ends the session and discards the actions not yet handled. Open, handle, tick and close on the
 * host's game thread; read [value] in composition and call [send] from UI callbacks.
 *
 * Hosts drive it for their `snapshot`, `handle` and `Content` members, so game code rarely uses it directly.
 */
class UiStateBinding<S, A>(private val snapshot: () -> S, private val handle: (A) -> Unit) {
    // The session's binding. It stays readable after close, while the session's composition is disposed.
    @Volatile private var binding: UiBinding<S, A>? = null

    /** True from [open] until [close]. Game thread. */
    var isOpen = false
        private set

    /** Starts a session from a new snapshot; does nothing while a session is open. */
    fun open() {
        if (isOpen) return
        binding = UiBinding(snapshot())
        isOpen = true
    }

    /** Handles the actions pending on entry, then publishes a snapshot unless [handle] ended the session. */
    fun tick() {
        if (!isOpen) return
        val current = checkNotNull(binding)
        current.drainActions { handle(it) }
        if (isOpen && binding === current) current.update(snapshot())
    }

    /**
     * Handles the actions pending on entry, such as those an input event just sent, and publishes a snapshot if any ran
     * and [handle] did not end the session. Without pending actions it takes no snapshot.
     */
    fun handleActions() {
        if (!isOpen) return
        val current = checkNotNull(binding)
        if (current.drainActions { handle(it) } > 0 && isOpen && binding === current) current.update(snapshot())
    }

    /** Ends the session: pending actions are discarded and [send] returns false until the next [open]. */
    fun close() {
        if (!isOpen) return
        checkNotNull(binding).close()
        isOpen = false
    }

    /** The latest snapshot. Equal snapshots never recompose. */
    val value: S
        @Composable get() = checkNotNull(binding) { "Read the state inside its host's content" }.value

    /** Queues [action] for [handle]. False without an open session or while 64 actions wait; none is overwritten. */
    fun send(action: A): Boolean = binding?.send(action) == true

    /** Actions [send] refused in the latest session because 64 were already waiting. */
    val rejectedActions: Long
        get() = binding?.rejectedActions ?: 0
}
