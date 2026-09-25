package dev.composemc.neoforge

import dev.composemc.platform.*
import org.lwjgl.glfw.GLFW

/** The key GLFW reports, with letters and punctuation resolved through the active keyboard layout. */
internal fun uiKey(keyCode: Int, scanCode: Int): UiKey = uiKey(keyCode) { GLFW.glfwGetKeyName(keyCode, scanCode) }

/**
 * Resolves [keyCode] as [UiKey] documents. [layoutName] supplies the character the key types in the
 * active layout; it is asked only for letter and punctuation positions and may return null.
 */
internal fun uiKey(keyCode: Int, layoutName: () -> String?): UiKey {
    val position = positionKey(keyCode)
    if (!position.followsLayout) return position
    val name = layoutName() ?: return position
    if (name.codePointCount(0, name.length) != 1) return position
    return UiKey.layoutKey(name.codePointAt(0)) ?: position
}

/** The key at a GLFW key position, named after the US layout. */
internal fun positionKey(keyCode: Int): UiKey = when (keyCode) {
    in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> UiKey.entries[UiKey.A.ordinal + keyCode - GLFW.GLFW_KEY_A]
    in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> UiKey.entries[UiKey.DIGIT_0.ordinal + keyCode - GLFW.GLFW_KEY_0]
    in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12 -> UiKey.entries[UiKey.F1.ordinal + keyCode - GLFW.GLFW_KEY_F1]
    in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_9 -> UiKey.entries[UiKey.NUMPAD_0.ordinal + keyCode - GLFW.GLFW_KEY_KP_0]
    GLFW.GLFW_KEY_MINUS -> UiKey.MINUS; GLFW.GLFW_KEY_EQUAL -> UiKey.EQUALS
    GLFW.GLFW_KEY_LEFT_BRACKET -> UiKey.LEFT_BRACKET; GLFW.GLFW_KEY_RIGHT_BRACKET -> UiKey.RIGHT_BRACKET
    GLFW.GLFW_KEY_BACKSLASH -> UiKey.BACKSLASH; GLFW.GLFW_KEY_SEMICOLON -> UiKey.SEMICOLON
    GLFW.GLFW_KEY_APOSTROPHE -> UiKey.APOSTROPHE; GLFW.GLFW_KEY_GRAVE_ACCENT -> UiKey.GRAVE
    GLFW.GLFW_KEY_COMMA -> UiKey.COMMA; GLFW.GLFW_KEY_PERIOD -> UiKey.PERIOD; GLFW.GLFW_KEY_SLASH -> UiKey.SLASH
    GLFW.GLFW_KEY_ENTER -> UiKey.ENTER; GLFW.GLFW_KEY_ESCAPE -> UiKey.ESCAPE; GLFW.GLFW_KEY_TAB -> UiKey.TAB; GLFW.GLFW_KEY_SPACE -> UiKey.SPACE
    GLFW.GLFW_KEY_BACKSPACE -> UiKey.BACKSPACE; GLFW.GLFW_KEY_DELETE -> UiKey.DELETE; GLFW.GLFW_KEY_INSERT -> UiKey.INSERT
    GLFW.GLFW_KEY_LEFT -> UiKey.LEFT; GLFW.GLFW_KEY_RIGHT -> UiKey.RIGHT; GLFW.GLFW_KEY_UP -> UiKey.UP; GLFW.GLFW_KEY_DOWN -> UiKey.DOWN
    GLFW.GLFW_KEY_HOME -> UiKey.HOME; GLFW.GLFW_KEY_END -> UiKey.END; GLFW.GLFW_KEY_PAGE_UP -> UiKey.PAGE_UP; GLFW.GLFW_KEY_PAGE_DOWN -> UiKey.PAGE_DOWN
    GLFW.GLFW_KEY_KP_DECIMAL -> UiKey.NUMPAD_DECIMAL; GLFW.GLFW_KEY_KP_DIVIDE -> UiKey.NUMPAD_DIVIDE
    GLFW.GLFW_KEY_KP_MULTIPLY -> UiKey.NUMPAD_MULTIPLY; GLFW.GLFW_KEY_KP_SUBTRACT -> UiKey.NUMPAD_SUBTRACT
    GLFW.GLFW_KEY_KP_ADD -> UiKey.NUMPAD_ADD; GLFW.GLFW_KEY_KP_ENTER -> UiKey.NUMPAD_ENTER; GLFW.GLFW_KEY_KP_EQUAL -> UiKey.NUMPAD_EQUALS
    GLFW.GLFW_KEY_LEFT_SHIFT -> UiKey.SHIFT_LEFT; GLFW.GLFW_KEY_RIGHT_SHIFT -> UiKey.SHIFT_RIGHT
    GLFW.GLFW_KEY_LEFT_CONTROL -> UiKey.CTRL_LEFT; GLFW.GLFW_KEY_RIGHT_CONTROL -> UiKey.CTRL_RIGHT
    GLFW.GLFW_KEY_LEFT_ALT -> UiKey.ALT_LEFT; GLFW.GLFW_KEY_RIGHT_ALT -> UiKey.ALT_RIGHT
    GLFW.GLFW_KEY_LEFT_SUPER -> UiKey.META_LEFT; GLFW.GLFW_KEY_RIGHT_SUPER -> UiKey.META_RIGHT
    GLFW.GLFW_KEY_CAPS_LOCK -> UiKey.CAPS_LOCK; GLFW.GLFW_KEY_NUM_LOCK -> UiKey.NUM_LOCK; GLFW.GLFW_KEY_SCROLL_LOCK -> UiKey.SCROLL_LOCK
    GLFW.GLFW_KEY_PRINT_SCREEN -> UiKey.PRINT_SCREEN; GLFW.GLFW_KEY_PAUSE -> UiKey.PAUSE; GLFW.GLFW_KEY_MENU -> UiKey.MENU
    else -> UiKey.UNKNOWN
}

internal fun Int.toMouseButton(): MouseButton? = when (this) {
    GLFW.GLFW_MOUSE_BUTTON_LEFT -> MouseButton.LEFT
    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> MouseButton.RIGHT
    GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE
    else -> null
}

internal fun Int.toModifiers() = Modifiers(
    shift = and(GLFW.GLFW_MOD_SHIFT) != 0, control = and(GLFW.GLFW_MOD_CONTROL) != 0,
    alt = and(GLFW.GLFW_MOD_ALT) != 0, meta = and(GLFW.GLFW_MOD_SUPER) != 0,
)
