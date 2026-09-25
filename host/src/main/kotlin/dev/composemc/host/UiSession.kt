package dev.composemc.host

import androidx.compose.runtime.Composable
import dev.composemc.bridge.ComposeThread
import dev.composemc.bridge.SceneBridge
import dev.composemc.platform.*
import dev.composemc.render.RecordedFrame
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionState { ACTIVE, SUSPENDED, CLOSED }

/** Public Screen session. All game-facing calls except post() belong to the creating thread. */
class UiSession(
    viewport: Viewport,
    clipboard: ClipboardPort = MemoryClipboard(),
    private val commandCapacity: Int = 1024,
    private val commandsPerFrame: Int = 128,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val closed = AtomicBoolean()
    private val commands = ArrayDeque<Runnable>().also {
        require(commandCapacity in 1..65_536 && commandsPerFrame in 1..commandCapacity)
    }
    private val bridge = ComposeThread.call { SceneBridge(viewport, clipboard) }
    var state: SessionState = SessionState.ACTIVE
        private set
    init {
        try { ComposeThread.call { bridge.setContent(content) } }
        catch (error: Throwable) { ComposeThread.call { bridge.close() }; throw error }
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Session must be used on its owning thread" }
    private fun checkOpen() { checkOwner(); check(!closed.get()) { "Session is closed" } }

    /** False means closed/full. Accepted commands may be discarded on close; captured data must be immutable. */
    fun post(command: Runnable): Boolean = synchronized(commands) {
        if (closed.get() || commands.size >= commandCapacity) false
        else { commands.addLast(command); true }
    }
    val pendingCommands: Int get() = synchronized(commands) { commands.size }

    fun frame(timeNanos: Long = System.nanoTime()): RecordedFrame? {
        checkOpen()
        if (state != SessionState.ACTIVE) return null
        return ComposeThread.call {
            // New/reentrant posts wait for a later frame, even when the queue initially held one task.
            val count = synchronized(commands) { minOf(commandsPerFrame, commands.size) }
            repeat(count) { synchronized(commands) { commands.pollFirst() }?.run() }
            bridge.recordFrame(timeNanos).also { recordTextInput() }
        }
    }
    fun resize(viewport: Viewport) { checkOpen(); ComposeThread.call { bridge.resize(viewport) } }
    fun invalidate() { checkOpen(); ComposeThread.call { bridge.invalidate() } }
    fun configure(rtl: Boolean = false, layoutBounds: Boolean = false) { checkOpen(); ComposeThread.call { bridge.configure(rtl, layoutBounds) } }
    fun setFocused(focused: Boolean) {
        checkOpen()
        ComposeThread.call { bridge.setFocused(focused); recordTextInput() }
    }
    fun setActive(active: Boolean) {
        checkOpen()
        state = if (active) SessionState.ACTIVE else SessionState.SUSPENDED
        ComposeThread.call { bridge.setFocused(active); recordTextInput() }
    }
    fun pointer(event: PointerInput): Boolean { checkOpen(); return state == SessionState.ACTIVE && ComposeThread.call { bridge.pointer(event) } }
    fun key(event: KeyInput): Boolean { checkOpen(); return state == SessionState.ACTIVE && ComposeThread.call { bridge.key(event) } }
    fun commitText(text: String): Boolean { checkOpen(); return state == SessionState.ACTIVE && ComposeThread.call { bridge.commitText(text) } }
    /** Shows [text] from an input method as the focused field's composition; null removes the composition. */
    fun setComposingText(text: ComposingText?): Boolean {
        checkOpen()
        return state == SessionState.ACTIVE && ComposeThread.call { bridge.setComposingText(text) }
    }
    fun diagnosticThread(): String { checkOpen(); return ComposeThread.call { bridge.diagnosticThread } }
    /** Use to give a focused Compose editor priority over game shortcuts. */
    val hasTextInputFocus: Boolean get() { checkOpen(); return ComposeThread.call { bridge.hasTextInputFocus } }
    /**
     * [hasTextInputFocus] as of the latest [frame] or focus change. Reading it never waits for Compose,
     * so a host can check it every frame, for example to open the platform's text input.
     */
    @Volatile var lastTextInputFocus = false
        private set
    /** The focused field's caret as of the latest [frame] or focus change, or null without [lastTextInputFocus]. */
    @Volatile var lastTextInputArea: TextInputArea? = null
        private set
    // Runs on the Compose thread, inside calls the host makes anyway.
    private fun recordTextInput() {
        lastTextInputFocus = bridge.textInputFocused
        lastTextInputArea = bridge.textInputArea
    }
    override fun close() {
        checkOwner()
        if (!closed.compareAndSet(false, true)) return
        state = SessionState.CLOSED
        lastTextInputFocus = false
        lastTextInputArea = null
        synchronized(commands) { commands.clear() }
        ComposeThread.call { bridge.close() }
    }
}
