package dev.compixel.testing.ui

import dev.compixel.platform.Modifiers

/**
 * Modifier of Compose's text shortcuts such as select all, copy, cut and paste: Command on macOS, Control elsewhere.
 * Compose picks its key mapping by the same `os.name` test.
 */
val textShortcut: Modifiers =
    if (System.getProperty("os.name") == "Mac OS X") Modifiers(meta = true) else Modifiers(control = true)
