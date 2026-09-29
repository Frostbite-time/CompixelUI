package dev.compixel.host

import dev.compixel.platform.ComposingText
import dev.compixel.platform.TextInputArea

/**
 * Connects a [UiSession] to its host's text input when the session shares a window with native widgets. The host's text
 * input is open while a Compose text field has focus; a focused native widget manages it instead. While it is open, the
 * input method's composition shows inside the field and its candidate window follows the caret. A pointer press or
 * native focus discards an unconfirmed composition instead of typing it, as losing window focus does in [UiSession].
 * Call every member on the session's thread.
 */
class HostTextInput(private val target: Target) {
    /** The host's text input switch and input method. */
    interface Target {
        /** Opens or closes the host's text input, which enables or disables its input method. */
        fun setOpen(open: Boolean)

        /** Places the input method's candidate window next to [area], given in viewport pixels. */
        fun setArea(area: TextInputArea)

        /** Makes the input method drop the text it is composing. */
        fun cancelComposing()
    }

    /** Whether the session holds the host's text input. */
    var open = false
        private set

    private var nativeFocus = false
    private var composing = false
    private var area: TextInputArea? = null

    /** Call after each frame: text field focus can move without input, and the caret moves with it. */
    fun afterFrame(session: UiSession?) {
        sync(session)
        if (!open || nativeFocus) return
        val current = session?.lastTextInputArea ?: return
        if (current == area) return
        area = current
        target.setArea(current)
    }

    /**
     * Call after the host's native focus changes. A focused native widget receives keys and manages the text input, and
     * the session's composition ends. The widget may close the text input when it lets go, so a Compose text field that
     * still has focus opens it again.
     */
    fun nativeFocusChanged(session: UiSession?, focused: Boolean) {
        if (focused == nativeFocus) return
        nativeFocus = focused
        if (focused) {
            discard(session)
            return
        }
        area = null
        if (open && session?.lastTextInputFocus == true) target.setOpen(true) else sync(session)
    }

    /** Shows [text] from the input method inside the focused field; null ends the composition. */
    fun setComposingText(session: UiSession?, text: ComposingText?): Boolean {
        if (text == null && !composing) return false
        val consumed = session?.setComposingText(text) == true
        composing = text != null && consumed
        return consumed
    }

    /** Call before a pointer press reaches the session: the press discards an unconfirmed composition. */
    fun discard(session: UiSession?) {
        if (!composing) return
        composing = false
        if (session?.setComposingText(null) == true) target.cancelComposing()
    }

    /** Call when the session closes. */
    fun close() {
        composing = false
        area = null
        if (open) {
            open = false
            target.setOpen(false)
        }
    }

    private fun sync(session: UiSession?) {
        if (nativeFocus) return
        val wanted = session?.lastTextInputFocus == true
        if (wanted == open) return
        open = wanted
        area = null
        composing = false // Opening or closing the text input starts the input method afresh.
        target.setOpen(wanted)
    }
}
