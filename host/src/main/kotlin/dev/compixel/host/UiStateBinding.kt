package dev.compixel.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.compixel.bridge.ComposeThread
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.properties.ReadOnlyProperty

/**
 * Game state for a host's content, renewed with each of the host's UI sessions. The content reads the game through
 * [gameState] subscriptions and the host's [snapshot], and changes it with [gameThread] tasks and actions for [handle].
 * Actions and tasks share one queue of at most 64 and run in the order they were queued; each step runs those waiting
 * when it starts, so what they queue in turn waits for the next step.
 * - [open] takes the first snapshot and samples the member subscriptions before the session composes.
 * - Each [tick] runs the waiting actions and tasks, then publishes a snapshot and samples the subscriptions due after
 *   their interval, those not sampled yet, and all of them when an action or task ran.
 * - [handleActions] runs the waiting actions and tasks, as a host does after each input event; when any ran it
 *   publishes a snapshot and samples every subscription.
 * - [resume] does the same whether or not any ran, for a session shown again after another screen covered its host.
 * - [close] ends the session and discards the queue; the content keeps showing the last values, for example in its
 *   exit.
 *
 * A step publishes what changed in one call to the Compose thread, and stops once an action or task ended the session.
 * Open, tick, handle, resume and close on the host's game thread; read [value] in composition and use [send] and
 * [gameThread] from UI callbacks.
 *
 * Hosts drive it for their `snapshot`, `handle`, `gameState` and `Content` members, so game code rarely uses it
 * directly.
 */
class UiStateBinding<S, A>(private val snapshot: () -> S, private val handle: (A) -> Unit) {
    // The session's queue of actions and tasks. It stays readable after close, while the session's composition is
    // disposed.
    @Volatile private var binding: UiBinding<S, Any?>? = null
    // Member subscriptions are declared on the thread that creates the binding, before its first session.
    private val creator = Thread.currentThread()
    @Volatile private var opened = false
    private val members = CopyOnWriteArrayList<Subscription<*>>()
    // Subscriptions the composition remembered, waiting on the Compose thread for the next step to take them.
    private val remembered = ConcurrentLinkedQueue<Subscription<*>>()
    private val composed = ArrayList<Subscription<*>>()
    // Changes when a session starts and ends, so a subscription of one session is never sampled into another.
    @Volatile private var generation = 0L
    private var ticks = 0L

    /** True from [open] until [close]. Game thread. */
    var isOpen = false
        private set

    /** Queues tasks with the content's actions; see [GameThread.post]. */
    val gameThread = GameThread { task -> binding?.send(Task(task)) == true }

    /** Starts a session from a new snapshot and samples the member subscriptions; does nothing while one is open. */
    fun open() {
        if (isOpen) return
        opened = true
        generation++
        ticks = 0
        remembered.clear()
        composed.clear()
        val opening = UiBinding<S, Any?>(snapshot())
        val changes = ArrayList<() -> Unit>()
        for (member in members) sample(member, changes)
        publish(changes)
        binding = opening
        isOpen = true
    }

    /**
     * Runs the actions and tasks waiting on entry, then publishes a snapshot and samples the subscriptions that are
     * due, or all of them when one ran, unless one of them ended the session.
     */
    fun tick() {
        if (!isOpen) return
        val current = checkNotNull(binding)
        ticks++
        val ran = current.drainActions { dispatch(it) }
        if (isOpen && binding === current) refresh(current, all = ran > 0)
    }

    /**
     * Runs the actions and tasks waiting on entry, such as those an input event just queued. When any ran and none
     * ended the session, publishes a snapshot and samples every subscription; otherwise it reads nothing.
     */
    fun handleActions() {
        if (!isOpen) return
        val current = checkNotNull(binding)
        if (current.drainActions { dispatch(it) } > 0 && isOpen && binding === current) refresh(current, all = true)
    }

    /**
     * Runs the waiting actions and tasks of a session shown again after another screen covered its host, then publishes
     * a snapshot and samples every subscription, since the game went on meanwhile.
     */
    fun resume() {
        if (!isOpen) return
        val current = checkNotNull(binding)
        current.drainActions { dispatch(it) }
        if (isOpen && binding === current) refresh(current, all = true)
    }

    /** Ends the session: the queue is discarded and [send] and [gameThread] return false until the next [open]. */
    fun close() {
        if (!isOpen) return
        checkNotNull(binding).close()
        isOpen = false
        generation++
        remembered.clear()
        composed.clear()
    }

    /** The latest snapshot. Equal snapshots never recompose. */
    val value: S
        @Composable get() = checkNotNull(binding) { "Read the state inside its host's content" }.value

    /**
     * Queues [action] for [handle]. False without an open session or while 64 actions and tasks wait; none is
     * overwritten.
     */
    fun send(action: A): Boolean = binding?.send(action) == true

    /** Actions and tasks refused in the latest session because 64 were already waiting. */
    val rejectedActions: Long
        get() = binding?.rejectedActions ?: 0

    /**
     * Game state for a host's member property: [read] takes it on the game thread when each session opens, before the
     * session composes, then every [intervalTicks] client ticks and after every action or task, and the delegate shows
     * the latest result. Reading the delegate never runs [read]; in composition, readers recompose when the value
     * changes. Declare it on the thread that creates the binding before its first session, for example while the host
     * is constructed; in composition use the composable gameState with an initial value.
     *
     * [read] only reads the game. Return values and immutable data, never game objects such as entities, menus or item
     * stacks; read what has to stay consistent in one block.
     */
    fun <T> gameState(intervalTicks: Int = 1, read: () -> T): ReadOnlyProperty<Any?, T> {
        require(intervalTicks >= 1) { "intervalTicks must be at least 1" }
        check(Thread.currentThread() === creator && !opened) {
            "Declare member gameState while the host is constructed; in composition use gameState(initialValue = ...)"
        }
        val member = Subscription(read, intervalTicks, Unsampled)
        members += member
        return ReadOnlyProperty { _, property ->
            val shown = member.shown
            check(shown !== Unsampled) { "${property.name} was read before its host opened" }
            @Suppress("UNCHECKED_CAST")
            shown as T
        }
    }

    /** A subscription for the composable gameState; the next step takes it once the composition remembers it. */
    internal fun <T> composedSubscription(initialValue: T, intervalTicks: Int, read: () -> T): Subscription<T> {
        require(intervalTicks >= 1) { "intervalTicks must be at least 1" }
        return ComposedSubscription(read, intervalTicks, initialValue)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(entry: Any?) = if (entry is Task) entry.task.run() else handle(entry as A)

    // Publishes a snapshot with the subscriptions that are due, or all of them.
    private fun refresh(current: UiBinding<S, Any?>, all: Boolean) {
        takeRemembered()
        val changes = ArrayList<() -> Unit>()
        current.stage(snapshot())?.let(changes::add)
        for (member in members) if (all || member.dueAt(ticks)) sample(member, changes)
        for (subscription in composed) if (all || subscription.dueAt(ticks)) sample(subscription, changes)
        publish(changes)
    }

    // Takes the subscriptions the composition remembered since the last step and drops those it forgot.
    private fun takeRemembered() {
        while (true) {
            val next = remembered.poll() ?: break
            if (next.generation == generation && next.active) composed += next
        }
        composed.removeAll { !it.active }
    }

    private fun sample(subscription: Subscription<*>, changes: MutableList<() -> Unit>) {
        val value = subscription.read()
        subscription.sampledAt = ticks
        if (value == subscription.published) return
        subscription.published = value
        changes += { subscription.show(value) }
    }

    // One call to the Compose thread shows all of a step's changes, so the content recomposes once.
    private fun publish(changes: List<() -> Unit>) {
        if (changes.isNotEmpty()) ComposeThread.call { changes.forEach { it() } }
    }

    // A queued task, told apart from the host's actions, whatever their type.
    private class Task(val task: Runnable)

    // Taken by the next step once the composition remembers it, and no longer sampled once the composition forgets it.
    private inner class ComposedSubscription<T>(read: () -> T, interval: Int, initial: T) :
        Subscription<T>(read, interval, initial), RememberObserver {
        override fun onRemembered() {
            generation = this@UiStateBinding.generation
            remembered += this
        }

        override fun onForgotten() {
            active = false
        }

        override fun onAbandoned() {
            active = false
        }
    }
}

/**
 * One gameState value: its read block, sampled on the game thread, and the Compose state that shows the result. As a
 * [State] it reads the shown value.
 */
internal open class Subscription<T>(read: () -> T, private val interval: Int, initial: Any?) : State<T> {
    @Volatile var read: () -> T = read
    @Volatile var active = true
    // The session that remembered it, for composed subscriptions.
    var generation = 0L
    private val state = mutableStateOf(initial)
    // Game thread: the value last published, and the tick of the last sample.
    var published: Any? = initial
    var sampledAt = NEVER

    val shown: Any?
        get() = state.value

    @Suppress("UNCHECKED_CAST")
    override val value: T
        get() = state.value as T

    fun dueAt(tick: Long) = sampledAt == NEVER || tick - sampledAt >= interval

    /** Compose thread. */
    fun show(value: Any?) {
        state.value = value
    }
}

private const val NEVER = Long.MIN_VALUE

// A member subscription's value before its first sample.
private val Unsampled = Any()
