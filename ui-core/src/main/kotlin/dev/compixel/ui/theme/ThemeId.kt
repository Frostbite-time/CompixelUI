package dev.compixel.ui.theme

import androidx.compose.runtime.Immutable

/**
 * A theme's name: the file `assets/<namespace>/compixel/themes/<path>.json` in each resource pack. Paths may include
 * slash-separated subdirectories.
 */
@Immutable
data class ThemeId(val namespace: String, val path: String = "default") {
    init {
        require(namespace.matches(Regex("[a-z0-9_.-]+"))) { "Invalid theme namespace: $namespace" }
        require(
            path.matches(Regex("[a-z0-9_./-]+")) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
        ) {
            "Invalid theme path: $path"
        }
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        /** The global default, which every other theme starts from unless a section gives it a built-in start. */
        val Default = ThemeId("compixel")

        /** CompixelUI's built-in light theme; sections may start it from their own light values. */
        val Light = ThemeId("compixel", "light")

        /** CompixelUI's built-in twilight theme; sections may start it from their own twilight values. */
        val Twilight = ThemeId("compixel", "twilight")
    }
}
