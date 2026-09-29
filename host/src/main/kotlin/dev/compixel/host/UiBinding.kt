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
            if (closed || actions.size >= capacity) false
            else {
                actions.addLast(action)
                true
            }
        }

    /** Dispatch at most the actions pending on entry; callbacks run on the creating thread. */
    fun drainActions(handler: Consumer<A>) {
        checkOwner()
        val count = synchronized(actions) { if (closed) 0 else actions.size }
        repeat(count) {
            val action = synchronized(actions) { if (closed) null else actions.pollFirst() } ?: return
            handler.accept(action)
        }
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
