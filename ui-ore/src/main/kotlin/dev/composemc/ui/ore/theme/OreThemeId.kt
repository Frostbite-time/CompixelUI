package dev.composemc.ui.ore.theme

import androidx.compose.runtime.Immutable

/** Resource identity, independent of Minecraft. Paths may include slash-separated subdirectories. */
@Immutable
data class OreThemeId(val namespace: String, val path: String = "default") {
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
        val Default = OreThemeId("composemc")
        val Light = OreThemeId("composemc", "light")
        val Twilight = OreThemeId("composemc", "twilight")
    }
}
