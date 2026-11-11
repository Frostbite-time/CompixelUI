package dev.compixel.host

import androidx.compose.runtime.Composable
import dev.compixel.bridge.SceneBridge
import dev.compixel.platform.*
import dev.compixel.render.RecordedFrame
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionState {
    ACTIVE,
    CLOSED,
}

/**
 * A Compose scene and its content on the host's [thread], the thread that creates the session: input, composition,
 * layout and drawing all run inside the calls the host makes. Every member except [post] belongs to that thread, and to
 * the host: content that calls back into its own session from a callback, or closes it, fails. It queues such work for
 * after the event, as [UiStateBinding.gameThread] and [post] do.
 */
class UiSession(
    viewport: Viewport,
    clipboard: ClipboardPort = MemoryClipboard(),
    private val commandCapacity: Int = 1024,
    private val commandsPerFrame: Int = 128,
    private val thread: UiThread = UiThread.current(),
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val commands =
        ArrayDeque<Runnable>().also {
            require(commandCapacity in 1..65_536 && commandsPerFrame in 1..commandCapacity)
        }
    private val bridge = SceneBridge(viewport, clipboard, thread)
    var state: SessionState = SessionState.ACTIVE
        private set

    init {
        try {
            bridge.setContent(content)
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
    }

    private fun checkOpen() {
        thread.check()
        check(!closed.get()) { "Session is closed" }
    }

    /**
     * Runs [command] on the session's thread at its next frame, from any thread. False means closed/full. Accepted
     * commands may be discarded on close.
     */
    fun post(command: Runnable): Boolean =
        synchronized(commands) {
            if (closed.get() || commands.size >= commandCapacity) false
            else {
                commands.addLast(command)
                true
            }
        }

    val pendingCommands: Int
        get() = synchronized(commands) { commands.size }

    /** [update], then [record]: one whole frame, for a host that reads nothing from the layout in between. */
    fun frame(timeNanos: Long = System.nanoTime()): RecordedFrame? {
        update(timeNanos)
        return record()
    }

    /**
     * Runs the posted commands, then brings the content to [timeNanos]: effects and animations run, what changed is
     * composed and laid out, and the host can read this frame's layout before [record] draws it. After the host wrote
     * state from that layout, another call with the same time composes and lays that out too. False when nothing was
     * pending, so the content stayed as it was.
     */
    fun update(timeNanos: Long = System.nanoTime()): Boolean {
        checkOpen()
        thread.runPending()
        // New/reentrant posts wait for a later frame, even when the queue initially held one task.
        val count = synchronized(commands) { minOf(commandsPerFrame, commands.size) }
        repeat(count) { synchronized(commands) { commands.pollFirst() }?.run() }
        return bridge.update(timeNanos)
    }

    /** Draws the content into a frame when it changed since the last one; null otherwise. */
    fun record(): RecordedFrame? {
        checkOpen()
        return bridge.record()
    }

    fun resize(viewport: Viewport) {
        checkOpen()
        bridge.resize(viewport)
    }

    /** Makes the next [update] and [record] run even when nothing in the content changed. */
    fun invalidate() {
        checkOpen()
        bridge.invalidate()
    }

    fun configure(rtl: Boolean = false, layoutBounds: Boolean = false) {
        checkOpen()
        bridge.configure(rtl, layoutBounds)
    }

    fun setFocused(focused: Boolean) {
        checkOpen()
        bridge.setFocused(focused)
    }

    fun pointer(event: PointerInput): Boolean {
        checkOpen()
        return bridge.pointer(event)
    }

    fun key(event: KeyInput): Boolean {
        checkOpen()
        return bridge.key(event)
    }

    fun commitText(text: String): Boolean {
        checkOpen()
        return bridge.commitText(text)
    }

    /** Shows [text] from an input method as the focused field's composition; null removes the composition. */
    fun setComposingText(text: ComposingText?): Boolean {
        checkOpen()
        return bridge.setComposingText(text)
    }

    /**
     * Use to give a focused Compose editor priority over game shortcuts. Composes pending state first, so it already
     * counts a field that the latest input focused.
     */
    val hasTextInputFocus: Boolean
        get() {
            checkOpen()
            return bridge.hasTextInputFocus
        }

    /** [hasTextInputFocus] as the content stands, for example to open the platform's text input after each frame. */
    val textInputFocused: Boolean
        get() = !closed.get() && bridge.textInputFocused

    /** The focused field's caret in viewport pixels, or null without [textInputFocused]. */
    val textInputArea: TextInputArea?
        get() = if (closed.get()) null else bridge.textInputArea

    override fun close() {
        thread.check()
        if (closed.get()) return
        // Refused while the content runs: a callback that closes its own session leaves it open and fails.
        bridge.close()
        closed.set(true)
        state = SessionState.CLOSED
        synchronized(commands) { commands.clear() }
        thread.runPending() // Cancelled effects finish on a thread without a task loop.
    }
}
