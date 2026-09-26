package dev.composemc.forge.input

/** Joins the UTF-16 units of each typed code point and drops control characters. */
internal class CommittedCharacters {
    private var pendingHigh: Char? = null

    fun accept(character: Char): String? {
        val high = pendingHigh
        pendingHigh = null
        return when {
            character.isHighSurrogate() -> {
                pendingHigh = character
                null
            }
            character.isLowSurrogate() -> high?.let { "$it$character" }
            character.isISOControl() -> null
            else -> character.toString()
        }
    }

    fun reset() {
        pendingHigh = null
    }
}
