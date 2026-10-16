package dev.compixel.ui

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed

/**
 * The key that holds Ctrl's role in shortcuts: Command on macOS, Ctrl elsewhere. Compose's text fields use the same key
 * for copy, paste and select all, and Ore's number fields take their large step with it. Check it with
 * [isCtrlOrCmdPressed] rather than a fixed modifier.
 */
object CtrlOrCmd {
    /** Whether the key is Command, as on macOS. Compose identifies macOS by the same `os.name` test. */
    val isCmd: Boolean = System.getProperty("os.name") == "Mac OS X"
}

/** Whether [CtrlOrCmd] is held during this key event: Command on macOS, Ctrl elsewhere. */
val KeyEvent.isCtrlOrCmdPressed: Boolean
    get() = if (CtrlOrCmd.isCmd) isMetaPressed else isCtrlPressed

/** Whether [CtrlOrCmd] is held during this pointer event: Command on macOS, Ctrl elsewhere. */
val PointerKeyboardModifiers.isCtrlOrCmdPressed: Boolean
    get() = if (CtrlOrCmd.isCmd) isMetaPressed else isCtrlPressed
