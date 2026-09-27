package dev.composemc.ui.ore.theme

import androidx.compose.runtime.Immutable

/** An immutable, pre-resolved resource snapshot. Rebuild from the current resources on every reload. */
@Immutable
class OreThemeCatalog private constructor(private val themes: Map<OreThemeId, OreColors>) {
    /** Unknown names inherit their namespace default, then the global default. No disk access or mutation. */
    fun colors(id: OreThemeId): OreColors =
        themes[id] ?: themes[OreThemeId(id.namespace)] ?: themes.getValue(OreThemeId.Default)

    override fun equals(other: Any?): Boolean = other is OreThemeCatalog && themes == other.themes

    override fun hashCode(): Int = themes.hashCode()

    companion object {
        val Default: OreThemeCatalog = create(emptyMap())

        /** Each list is ordered from the lowest to highest resource-pack priority. */
        fun create(layers: Map<OreThemeId, List<OreThemePatch>>): OreThemeCatalog {
            fun apply(id: OreThemeId, base: OreColors) =
                layers[id].orEmpty().fold(base) { colors, patch -> patch.applyTo(colors) }
            val global = apply(OreThemeId.Default, OreColors())
            val resolved = linkedMapOf(OreThemeId.Default to global)
            layers.keys
                .map { it.namespace }
                .distinct()
                .filter { it != "composemc" }
                .forEach { namespace ->
                    val id = OreThemeId(namespace)
                    resolved[id] = apply(id, global)
                }
            // Built-in themes start from their own palette rather than the global default.
            val builtIns = mapOf(OreThemeId.Light to OrePalettes.Light, OreThemeId.Twilight to OrePalettes.Twilight)
            builtIns.forEach { (id, palette) -> resolved[id] = apply(id, palette) }
            layers.keys
                .filter { it.path != "default" && it !in builtIns }
                .forEach { id ->
                    resolved[id] = apply(id, resolved[OreThemeId(id.namespace)] ?: global)
                }
            return OreThemeCatalog(resolved.toMap())
        }
    }
}
