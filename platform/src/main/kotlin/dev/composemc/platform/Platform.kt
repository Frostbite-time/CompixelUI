package dev.composemc.platform

import java.util.concurrent.atomic.AtomicReference

data class Viewport(val width: Int, val height: Int, val density: Float = 1f) {
    init {
        require(width > 0 && height > 0) { "Viewport must have positive pixel dimensions" }
        require(density.isFinite() && density > 0) { "Density must be finite and positive" }
    }

    fun guiToPixel(value: Double): Float = (value * density).toFloat()
}

interface ClipboardPort {
    fun readText(): String

    fun writeText(text: String)
}

class MemoryClipboard : ClipboardPort {
    private val text = AtomicReference("")

    override fun readText(): String = text.get()

    override fun writeText(text: String) {
        this.text.set(text)
    }
}

data class Modifiers(
    val shift: Boolean = false,
    val control: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

enum class PointerAction {
    MOVE,
    PRESS,
    RELEASE,
    SCROLL,
    EXIT,
}

enum class MouseButton {
    LEFT,
    RIGHT,
    MIDDLE,
}

data class PointerInput(
    val action: PointerAction,
    val x: Float,
    val y: Float,
    val button: MouseButton? = null,
    val scrollX: Float = 0f,
    val scrollY: Float = 0f,
    val modifiers: Modifiers = Modifiers(),
    val timeMillis: Long = System.nanoTime() / 1_000_000,
)

/**
 * Host-neutral identity of a key on a standard keyboard, for key events and shortcuts. Typed text arrives separately as
 * committed characters. Letters and punctuation follow the active keyboard layout, as on common desktop platforms: a
 * host reports the key that types that character, and keeps the US position where the layout types no Latin character
 * there. Digits and every other key keep their US position.
 */
enum class UiKey {
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,
    DIGIT_0,
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
    MINUS,
    EQUALS,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    BACKSLASH,
    SEMICOLON,
    APOSTROPHE,
    GRAVE,
    COMMA,
    PERIOD,
    SLASH,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
    ENTER,
    ESCAPE,
    TAB,
    SPACE,
    BACKSPACE,
    DELETE,
    INSERT,
    LEFT,
    RIGHT,
    UP,
    DOWN,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    NUMPAD_0,
    NUMPAD_1,
    NUMPAD_2,
    NUMPAD_3,
    NUMPAD_4,
    NUMPAD_5,
    NUMPAD_6,
    NUMPAD_7,
    NUMPAD_8,
    NUMPAD_9,
    NUMPAD_DECIMAL,
    NUMPAD_DIVIDE,
    NUMPAD_MULTIPLY,
    NUMPAD_SUBTRACT,
    NUMPAD_ADD,
    NUMPAD_ENTER,
    NUMPAD_EQUALS,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    CTRL_LEFT,
    CTRL_RIGHT,
    ALT_LEFT,
    ALT_RIGHT,
    META_LEFT,
    META_RIGHT,
    CAPS_LOCK,
    NUM_LOCK,
    SCROLL_LOCK,
    PRINT_SCREEN,
    PAUSE,
    MENU,
    UNKNOWN;

    /** Whether hosts resolve this key through the active layout with [layoutKey]. */
    val followsLayout: Boolean
        get() = this in A..Z || this in MINUS..SLASH

    companion object {
        /** The letter or punctuation key that types [codePoint] unshifted, or null for any other character. */
        fun layoutKey(codePoint: Int): UiKey? =
            when (val character = Character.toLowerCase(codePoint)) {
                in 'a'.code..'z'.code -> entries[A.ordinal + character - 'a'.code]
                '-'.code -> MINUS
                '='.code -> EQUALS
                '['.code -> LEFT_BRACKET
                ']'.code -> RIGHT_BRACKET
                '\\'.code -> BACKSLASH
                ';'.code -> SEMICOLON
                '\''.code -> APOSTROPHE
                '`'.code -> GRAVE
                ','.code -> COMMA
                '.'.code -> PERIOD
                '/'.code -> SLASH
                else -> null
            }
    }
}

data class KeyInput(val key: UiKey, val pressed: Boolean, val modifiers: Modifiers = Modifiers())

/**
 * Text an input method is composing but has not committed, such as pinyin before a candidate is chosen. Hosts show it
 * inside the focused text field; [cursor] is a UTF-16 index into [text].
 */
data class ComposingText(val text: String, val cursor: Int = text.length) {
    init {
        require(text.isNotEmpty() && cursor in 0..text.length) { "Composing text needs a cursor inside non-empty text" }
    }
}

/** A text field's caret in viewport pixels. Hosts place an input method's candidate window next to it. */
data class TextInputArea(val left: Float, val top: Float, val right: Float, val bottom: Float)
