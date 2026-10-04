package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import dev.compixel.bridge.ComposeThread
import java.util.ArrayDeque
import java.util.function.Consumer

/**
 * Immutable business snapshots into Compose, typed actions back to the creating thread. Construct, update, drain and
 * close on the business/host thread. Compose callbacks call send(). Snapshots and actions must not expose mutable game
 * objects to the composition.
 */
class UiBinding<S, A>(initial: S, private val capacity: Int = 64) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val actions = ArrayDeque<A>()
    private var closed = false
    private var rejected = 0L
    private var latest = initial
    private val state = mutableStateOf(initial)

    init {
        require(capacity > 0) { "Action capacity must be positive" }
    }

    val value: S
        @Composable get() = state.value

    /** Equal snapshots do not cross threads or invalidate the composition. */
    fun update(snapshot: S) {
        checkOwner()
        check(!synchronized(actions) { closed }) { "Binding is closed" }
        if (latest == snapshot) return
        ComposeThread.call { state.value = snapshot }
        latest = snapshot
    }

    /** False means the screen closed or the bounded queue is full; no action is overwritten. */
    fun send(action: A): Boolean =
        synchronized(actions) {
            when {
                closed -> false
                actions.size >= capacity -> {
                    rejected++
                    false
                }
                else -> {
                    actions.addLast(action)
                    true
                }
            }
        }

    /** Actions [send] refused because the queue was full. Sends after [close] are not counted. */
    val rejectedActions: Long
        get() = synchronized(actions) { rejected }

    /** Dispatch at most the actions pending on entry; callbacks run on the creating thread. Returns how many ran. */
    fun drainActions(handler: Consumer<A>): Int {
        checkOwner()
        val count = synchronized(actions) { if (closed) 0 else actions.size }
        var handled = 0
        repeat(count) {
            val action = synchronized(actions) { if (closed) null else actions.pollFirst() } ?: return handled
            handler.accept(action)
            handled++
        }
        return handled
    }

    override fun close() {
        checkOwner()
        synchronized(actions) {
            closed = true
            actions.clear()
        }
    }

    private fun checkOwner() =
        check(Thread.currentThread() === owner) {
            "Update, drain and close the binding on its creating thread"
        }
}
