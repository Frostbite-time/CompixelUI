package dev.composemc.neoforge

import com.mojang.blaze3d.platform.InputConstants
import dev.composemc.platform.*
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import org.lwjgl.sdl.SDLScancode

/** The key a Minecraft key event reports, resolved as [uiKey] describes. */
internal fun KeyEvent.toUiKey(): UiKey = uiKey(key(), keycode())

/**
 * Resolves an SDL key as [UiKey] documents. SDL reports both the physical [scancode] and the
 * [keycode] of the active layout, which for a printable key is the character it types unshifted.
 */
internal fun uiKey(scancode: Int, keycode: Int): UiKey {
    val position = positionKey(scancode)
    return if (position.followsLayout) UiKey.layoutKey(keycode) ?: position else position
}

/** The key at an SDL scancode, named after the US layout. */
internal fun positionKey(scancode: Int): UiKey = when (scancode) {
    in InputConstants.KEY_A..InputConstants.KEY_Z -> UiKey.entries[UiKey.A.ordinal + scancode - InputConstants.KEY_A]
    in InputConstants.KEY_F1..InputConstants.KEY_F12 -> UiKey.entries[UiKey.F1.ordinal + scancode - InputConstants.KEY_F1]
    // SDL places 0 after 9 on the number row and on the keypad.
    InputConstants.KEY_0 -> UiKey.DIGIT_0; InputConstants.KEY_1 -> UiKey.DIGIT_1; InputConstants.KEY_2 -> UiKey.DIGIT_2
    InputConstants.KEY_3 -> UiKey.DIGIT_3; InputConstants.KEY_4 -> UiKey.DIGIT_4; InputConstants.KEY_5 -> UiKey.DIGIT_5
    InputConstants.KEY_6 -> UiKey.DIGIT_6; InputConstants.KEY_7 -> UiKey.DIGIT_7; InputConstants.KEY_8 -> UiKey.DIGIT_8
    InputConstants.KEY_9 -> UiKey.DIGIT_9
    InputConstants.KEY_NUMPAD0 -> UiKey.NUMPAD_0; InputConstants.KEY_NUMPAD1 -> UiKey.NUMPAD_1; InputConstants.KEY_NUMPAD2 -> UiKey.NUMPAD_2
    InputConstants.KEY_NUMPAD3 -> UiKey.NUMPAD_3; InputConstants.KEY_NUMPAD4 -> UiKey.NUMPAD_4; InputConstants.KEY_NUMPAD5 -> UiKey.NUMPAD_5
    InputConstants.KEY_NUMPAD6 -> UiKey.NUMPAD_6; InputConstants.KEY_NUMPAD7 -> UiKey.NUMPAD_7; InputConstants.KEY_NUMPAD8 -> UiKey.NUMPAD_8
    InputConstants.KEY_NUMPAD9 -> UiKey.NUMPAD_9
    InputConstants.KEY_MINUS -> UiKey.MINUS; InputConstants.KEY_EQUALS -> UiKey.EQUALS
    InputConstants.KEY_LBRACKET -> UiKey.LEFT_BRACKET; InputConstants.KEY_RBRACKET -> UiKey.RIGHT_BRACKET
    InputConstants.KEY_BACKSLASH -> UiKey.BACKSLASH; InputConstants.KEY_SEMICOLON -> UiKey.SEMICOLON
    InputConstants.KEY_APOSTROPHE -> UiKey.APOSTROPHE; InputConstants.KEY_GRAVE -> UiKey.GRAVE
    InputConstants.KEY_COMMA -> UiKey.COMMA; InputConstants.KEY_PERIOD -> UiKey.PERIOD; InputConstants.KEY_SLASH -> UiKey.SLASH
    InputConstants.KEY_RETURN -> UiKey.ENTER; InputConstants.KEY_ESCAPE -> UiKey.ESCAPE; InputConstants.KEY_TAB -> UiKey.TAB
    InputConstants.KEY_SPACE -> UiKey.SPACE; InputConstants.KEY_BACKSPACE -> UiKey.BACKSPACE
    InputConstants.KEY_DELETE -> UiKey.DELETE; InputConstants.KEY_INSERT -> UiKey.INSERT
    InputConstants.KEY_LEFT -> UiKey.LEFT; InputConstants.KEY_RIGHT -> UiKey.RIGHT; InputConstants.KEY_UP -> UiKey.UP; InputConstants.KEY_DOWN -> UiKey.DOWN
    InputConstants.KEY_HOME -> UiKey.HOME; InputConstants.KEY_END -> UiKey.END
    InputConstants.KEY_PAGEUP -> UiKey.PAGE_UP; InputConstants.KEY_PAGEDOWN -> UiKey.PAGE_DOWN
    SDLScancode.SDL_SCANCODE_KP_PERIOD -> UiKey.NUMPAD_DECIMAL; SDLScancode.SDL_SCANCODE_KP_DIVIDE -> UiKey.NUMPAD_DIVIDE
    InputConstants.KEY_MULTIPLY -> UiKey.NUMPAD_MULTIPLY; SDLScancode.SDL_SCANCODE_KP_MINUS -> UiKey.NUMPAD_SUBTRACT
    InputConstants.KEY_ADD -> UiKey.NUMPAD_ADD; InputConstants.KEY_NUMPADENTER -> UiKey.NUMPAD_ENTER
    InputConstants.KEY_NUMPADEQUALS -> UiKey.NUMPAD_EQUALS
    InputConstants.KEY_LSHIFT -> UiKey.SHIFT_LEFT; InputConstants.KEY_RSHIFT -> UiKey.SHIFT_RIGHT
    InputConstants.KEY_LCONTROL -> UiKey.CTRL_LEFT; InputConstants.KEY_RCONTROL -> UiKey.CTRL_RIGHT
    InputConstants.KEY_LALT -> UiKey.ALT_LEFT; InputConstants.KEY_RALT -> UiKey.ALT_RIGHT
    InputConstants.KEY_LGUI -> UiKey.META_LEFT; InputConstants.KEY_RGUI -> UiKey.META_RIGHT
    InputConstants.KEY_CAPSLOCK -> UiKey.CAPS_LOCK; InputConstants.KEY_NUMLOCK -> UiKey.NUM_LOCK
    InputConstants.KEY_SCROLLLOCK -> UiKey.SCROLL_LOCK; InputConstants.KEY_PRINTSCREEN -> UiKey.PRINT_SCREEN
    InputConstants.KEY_PAUSE -> UiKey.PAUSE
    SDLScancode.SDL_SCANCODE_APPLICATION, SDLScancode.SDL_SCANCODE_MENU -> UiKey.MENU
    else -> UiKey.UNKNOWN
}

internal fun Int.toMouseButton(): MouseButton? = when (this) {
    InputConstants.MOUSE_BUTTON_LEFT -> MouseButton.LEFT
    InputConstants.MOUSE_BUTTON_RIGHT -> MouseButton.RIGHT
    InputConstants.MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE
    else -> null
}

internal fun Int.toModifiers() = Modifiers(
    shift = and(InputConstants.MOD_SHIFT) != 0, control = and(InputConstants.MOD_CONTROL) != 0,
    alt = and(InputConstants.MOD_ALT) != 0, meta = and(InputConstants.MOD_SUPER) != 0,
)

/** Tells Minecraft whether [owner] has a focused text field; SDL delivers typed text only while one does. */
internal fun textInputFocusChanged(owner: Any, focused: Boolean) =
    Minecraft.getInstance().textInputManager().onTextInputFocusChange(owner, focused)
