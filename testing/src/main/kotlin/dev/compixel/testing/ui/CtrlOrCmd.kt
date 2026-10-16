package dev.compixel.testing.ui

import dev.compixel.platform.Modifiers
import dev.compixel.ui.CtrlOrCmd

/** Host modifiers that hold [CtrlOrCmd]: Command on macOS, Control elsewhere. */
val ctrlOrCmd: Modifiers = if (CtrlOrCmd.isCmd) Modifiers(meta = true) else Modifiers(control = true)
