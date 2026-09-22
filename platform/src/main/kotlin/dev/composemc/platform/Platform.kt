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
    override fun writeText(text: String) { this.text.set(text) }
}
data class Modifiers(val shift: Boolean = false, val control: Boolean = false, val alt: Boolean = false, val meta: Boolean = false)
enum class PointerAction { MOVE, PRESS, RELEASE, SCROLL, EXIT }
enum class MouseButton { LEFT, RIGHT, MIDDLE }
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
enum class UiKey {
    A, C, V, X, Y, Z, ENTER, ESCAPE, TAB, SPACE, BACKSPACE, DELETE,
    LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN, UNKNOWN,
}
data class KeyInput(val key: UiKey, val pressed: Boolean, val modifiers: Modifiers = Modifiers())
