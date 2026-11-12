package dev.compixel.host

import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.util.ArrayDeque
import kotlin.properties.ReadOnlyProperty

/**
 * Game state for a host's content, renewed with each of the host's UI sessions. The content reads the game through
 * [gameState] subscriptions and changes it with [gameThread] tasks. At most 64 tasks wait, and they run in the order
 * they were queued; each step runs those waiting when it starts, so what they queue in turn waits for the next step.
 * - [open] samples the member subscriptions before the session composes.
 * - Each [tick] runs the waiting tasks, then samples the subscriptions due after their interval, those not sampled yet,
 *   and all of them when a task ran.
 * - [runTasks] runs the waiting tasks, as a host does after each input event; when any ran it samples every
 *   subscription.
 * - [resume] does the same whether or not any ran, for a session shown again after another screen covered its host.
 * - [close] ends the session and discards the queue; the content keeps showing the last values, for example in its
 *   exit.
 *
 * A step stops once a task ended the session. Everything but [GameThread.post] runs on the host's game thread, which is
 * also the thread its content composes on: what a step samples shows in the next frame.
 *
 * Hosts drive it for their `gameState`, `gameThread` and `Content` members, so game code rarely uses it directly.
 */
class UiStateBinding {
    // Tasks come from any thread; the lock is the queue itself.
    private val tasks = ArrayDeque<Runnable>()
    private var accepting = false
    private var rejected = 0L
    // Counts the sessions, so a step notices a task that ended its session, even when the task started another.
    private var sessions = 0
    private var opened = false
    private var owner: Thread? = null
    private val members = ArrayList<Subscription<*>>()
    // Subscriptions the composition remembers.
    private val composed = ArrayList<Subscription<*>>()
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

    /** Starts a session and samples the member subscriptions; does nothing while one is open. */
    fun open() {
        if (isOpen) return
        owner = Thread.currentThread()
        opened = true
        ticks = 0
        sessions++
        composed.clear()
        for (member in members) sample(member)
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
        composed.clear()
    }

    /** Tasks refused in the latest session because 64 were already waiting. */
    val rejectedTasks: Long
        get() = synchronized(tasks) { rejected }

    /**
     * Game state for a host's member property: [read] takes it on the game thread when each session opens, before the
     * session composes, then every [intervalTicks] client ticks and after every task, and the delegate shows the latest
     * result. Reading the delegate never runs [read]; in composition, readers recompose when the value changes. Declare
     * it before the binding's first session, for example while the host is constructed; in composition use the
     * composable gameState with an initial value.
     *
     * [read] only reads the game. Return values and immutable data, never game objects such as entities, menus or item
     * stacks; read what has to stay consistent in one block.
     */
    fun <T> gameState(intervalTicks: Int = 1, read: () -> T): ReadOnlyProperty<Any?, T> {
        require(intervalTicks >= 1) { "intervalTicks must be at least 1" }
        check(!opened) {
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
        composed.removeAll { !it.active }
        for (member in members) if (all || member.dueAt(ticks)) sample(member)
        // A read block cannot change which subscriptions the composition remembers.
        for (subscription in composed) if (all || subscription.dueAt(ticks)) sample(subscription)
    }

    private fun sample(subscription: Subscription<*>) {
        val value = subscription.read()
        subscription.sampledAt = ticks
        if (value == subscription.published) return
        subscription.published = value
        subscription.show(value)
    }

    // Sampled from the next step after the composition remembers it until the composition forgets it.
    private inner class ComposedSubscription<T>(read: () -> T, interval: Int, initial: T) :
        Subscription<T>(read, interval, initial), RememberObserver {
        override fun onRemembered() {
            // A session that already ended samples nothing; the next one starts without this subscription.
            if (isOpen) composed += this
        }

        override fun onForgotten() {
            active = false
        }

        override fun onAbandoned() {
            active = false
        }
    }

    private companion object {
        const val CAPACITY = 64
    }
}

/**
 * One gameState value: its read block, sampled on the game thread, and the Compose state that shows the result. As a
 * [State] it reads the shown value.
 */
internal open class Subscription<T>(read: () -> T, private val interval: Int, initial: Any?) : State<T> {
    var read: () -> T = read
    var active = true
    private val state = mutableStateOf(initial)
    // The value last published, and the tick of the last sample.
    var published: Any? = initial
    var sampledAt = NEVER

    val shown: Any?
        get() = state.value

    @Suppress("UNCHECKED_CAST")
    override val value: T
        get() = state.value as T

    fun dueAt(tick: Long) = sampledAt == NEVER || tick - sampledAt >= interval

    fun show(value: Any?) {
        state.value = value
    }
}

private const val NEVER = Long.MIN_VALUE

// A member subscription's value before its first sample.
private val Unsampled = Any()
