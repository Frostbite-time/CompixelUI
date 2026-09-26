package dev.composemc.forge.input

/** GLFW characters arrive as UTF-16 through Screen in 1.21.1. */
internal class CommittedCharacters {
    private var pendingHigh: Char? = null
    fun accept(character: Char): String? {
        val high = pendingHigh
        pendingHigh = null
        return when {
            character.isHighSurrogate() -> { pendingHigh = character; null }
            character.isLowSurrogate() -> high?.let { "$it$character" }
            character.isISOControl() -> null
            else -> character.toString()
        }
    }
    fun reset() { pendingHigh = null }
}
