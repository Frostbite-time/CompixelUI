package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import java.util.ArrayDeque

/**
 * Game state for a host's content, renewed with each of the host's UI sessions. The content reads the game through
 * [gameState] subscriptions and changes it with [gameThread] tasks. At most 64 tasks wait, and they run in the order
 * they were queued; each step runs those waiting when it starts, so what they queue in turn waits for the next step.
 * - [open] starts a session; each subscription reads its first value when the content remembers it.
 * - Each [tick] runs the waiting tasks, then samples the subscriptions due after their interval, and all of them when a
 *   task ran.
 * - [runTasks] runs the waiting tasks, as a host does after each input event; when any ran it samples every
 *   subscription.
 * - [resume] does the same whether or not any ran, for a session shown again after another screen covered its host.
 * - [close] ends the session and discards the queue; the content keeps showing the last values, for example in its
 *   exit.
 *
 * A step stops once a task ended the session. Everything but [GameThread.post] runs on the host's game thread, which is
 * also the thread its content composes on: what a step samples shows in the next frame.
 *
 * Hosts drive it for their `gameThread` and `Content` members, so game code rarely uses it directly.
 */
class UiStateBinding {
    // Tasks come from any thread; the lock is the queue itself.
    private val tasks = ArrayDeque<Runnable>()
    private var accepting = false
    private var rejected = 0L
    // Counts the sessions, so a step notices a task that ended its session, even when the task started another.
    private var sessions = 0
    private var owner: Thread? = null
    private val subscriptions = ArrayList<Subscription<*>>()
    private var ticks = 0L

    /** True from [open] until [close]. Game thread. */
    var isOpen = false
        private set

    /** Queues tasks for the game thread; see [GameThread.post]. */
    val gameThread = GameThread { task ->
        synchronized(tasks) {
            when {
                !accepting -> false
                tasks.size >= CAPACITY -> {
                    rejected++
                    false
                }
                else -> {
                    tasks.addLast(task)
                    true
                }
            }
        }
    }

    /** Starts a session; does nothing while one is open. */
    fun open() {
        if (isOpen) return
        owner = Thread.currentThread()
        ticks = 0
        sessions++
        subscriptions.clear()
        synchronized(tasks) {
            accepting = true
            rejected = 0
        }
        isOpen = true
    }

    /**
     * Runs the tasks waiting on entry, then samples the subscriptions that are due, or all of them when one ran, unless
     * one of them ended the session.
     */
    fun tick() {
        if (!isOpen) return
        checkOwner()
        val session = sessions
        ticks++
        val ran = runWaiting()
        if (isOpen && sessions == session) refresh(all = ran > 0)
    }

    /**
     * Runs the tasks waiting on entry, such as those an input event just queued. When any ran and none ended the
     * session, samples every subscription; otherwise it reads nothing.
     */
    fun runTasks() {
        if (!isOpen) return
        checkOwner()
        val session = sessions
        if (runWaiting() > 0 && isOpen && sessions == session) refresh(all = true)
    }

    /**
     * Runs the waiting tasks of a session shown again after another screen covered its host, then samples every
     * subscription, since the game went on meanwhile.
     */
    fun resume() {
        if (!isOpen) return
        checkOwner()
        val session = sessions
        runWaiting()
        if (isOpen && sessions == session) refresh(all = true)
    }

    /** Ends the session: the queue is discarded and [gameThread] returns false until the next [open]. */
    fun close() {
        if (!isOpen) return
        checkOwner()
        synchronized(tasks) {
            accepting = false
            tasks.clear()
        }
        isOpen = false
        subscriptions.clear()
    }

    /** Tasks refused in the latest session because 64 were already waiting. */
    val rejectedTasks: Long
        get() = synchronized(tasks) { rejected }

    /**
     * Observes [read] while this composition uses it: immediately, every [intervalTicks] client ticks and after tasks.
     * Changing [intervalTicks] or [keys] replaces the subscription with a freshly read value; leaving the composition
     * stops it. Use the top-level [dev.compixel.host.gameState] in a screen or HUD's content; an explicit binding lets
     * a host drive a separate group of subscriptions and tasks with its own lifecycle. Both run on this binding's
     * opening thread. Return immutable values and keep [read] free of side effects.
     */
    @Composable
    fun <T> gameState(intervalTicks: Int = 1, vararg keys: Any?, read: () -> T): State<T> {
        val subscription = remember(this, intervalTicks, *keys) { subscription(intervalTicks, read) }
        SideEffect { subscription.read = read }
        return subscription
    }

    private fun <T> subscription(intervalTicks: Int, read: () -> T): Subscription<T> {
        require(intervalTicks >= 1) { "intervalTicks must be at least 1" }
        checkOwner()
        check(isOpen) { "gameState needs an open host session" }
        return Subscription(read, intervalTicks, Snapshot.withoutReadObservation(read))
    }

    private fun checkOwner() =
        check(Thread.currentThread() === owner) { "Tick and close the game state on the thread that opened it" }

    // Runs the tasks waiting on entry and returns how many ran; stops when one of them ended the session.
    private fun runWaiting(): Int {
        val count = synchronized(tasks) { tasks.size }
        var ran = 0
        repeat(count) {
            val task = synchronized(tasks) { tasks.pollFirst() } ?: return ran
            task.run()
            ran++
        }
        return ran
    }

    // Samples the subscriptions that are due, or all of them.
    private fun refresh(all: Boolean) {
        // A read block cannot change which subscriptions the composition remembers.
        for (subscription in subscriptions) if (all || subscription.due()) subscription.sample()
    }

    /** One remembered game-state read and its latest value. */
    private inner class Subscription<T>(var read: () -> T, private val interval: Int, initial: T) :
        State<T>, RememberObserver {
        private val state = mutableStateOf(initial)
        private var sampledAt = ticks

        override val value: T
            get() = state.value

        fun due() = ticks - sampledAt >= interval

        fun sample() {
            val value = read()
            sampledAt = ticks
            state.value = value
        }

        override fun onRemembered() {
            // A session that already ended samples nothing; the next one starts without this subscription.
            if (isOpen) subscriptions += this
        }

        override fun onForgotten() {
            subscriptions.remove(this)
        }

        override fun onAbandoned() = Unit
    }

    private companion object {
        const val CAPACITY = 64
    }
}
