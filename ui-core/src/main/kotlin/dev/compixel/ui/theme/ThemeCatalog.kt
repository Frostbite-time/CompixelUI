package dev.compixel.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import java.util.concurrent.ConcurrentHashMap

/** One theme file from one resource pack: its decoded JSON object, and where it came from for messages. */
@Immutable data class ThemeLayer(val source: String, val document: Map<String, Any?>)

/**
 * The theme files of the current resources, resolved per [ThemeSection] when first read. A catalog never changes: hosts
 * build a new one after each resource reload.
 *
 * A theme's value for a section starts from the section's built-in value for that theme, otherwise from the theme's
 * namespace default `<namespace>:default`, otherwise from the global default [ThemeId.Default], which starts from
 * [ThemeSection.default]. The theme's own files then apply in resource-pack priority order, lowest first. A file whose
 * format is not [FORMAT] is ignored entirely, and a file whose section is invalid is ignored for that section only; the
 * catalog reports either once through its warning callback.
 */
@Immutable
class ThemeCatalog
private constructor(
    private val layers: Map<ThemeId, List<ThemeLayer>>,
    private val warn: (String) -> Unit,
) {
    private data class Key(val id: ThemeId, val section: ThemeSection<*>)

    private val resolved = ConcurrentHashMap<Key, Any>()

    /** The value of [section] for the theme [id]; a theme without files inherits as described above. */
    operator fun <T : Any> get(id: ThemeId, section: ThemeSection<T>): T {
        val key = Key(id, section)
        @Suppress("UNCHECKED_CAST")
        resolved[key]?.let {
            return it as T
        }
        var value =
            when {
                id == ThemeId.Default -> section.default
                else ->
                    section.builtIn(id)
                        ?: get(if (id.path == ThemeId.Default.path) ThemeId.Default else ThemeId(id.namespace), section)
            }
        for (layer in layers[id].orEmpty()) {
            if (section.name !in layer.document) continue
            value =
                try {
                    section.apply(value, layer.document[section.name])
                } catch (invalid: RuntimeException) {
                    warn("Ignoring the ${section.name} section of theme $id from ${layer.source}: ${invalid.message}")
                    value
                }
        }
        @Suppress("UNCHECKED_CAST")
        return (resolved.putIfAbsent(key, value) ?: value) as T
    }

    override fun equals(other: Any?): Boolean = other is ThemeCatalog && layers == other.layers

    override fun hashCode(): Int = layers.hashCode()

    companion object {
        /** The format of theme files, which each file states in its `format` field. */
        const val FORMAT = 1

        /** No theme files: every theme has each section's built-in and default values. */
        val Empty = ThemeCatalog(emptyMap()) {}

        /**
         * A catalog of theme files, each theme's list in resource-pack priority order from lowest to highest. Files
         * whose `format` is not [FORMAT] are reported through [warn] and left out; [warn] also receives the invalid
         * sections found later, on the thread that reads them.
         */
        fun create(layers: Map<ThemeId, List<ThemeLayer>>, warn: (String) -> Unit = {}): ThemeCatalog {
            val accepted = linkedMapOf<ThemeId, List<ThemeLayer>>()
            for ((id, files) in layers) {
                val valid = files.filter { layer ->
                    val format = layer.document["format"]
                    val supported = format is Number && format.toDouble() == FORMAT.toDouble()
                    if (!supported) warn("Ignoring theme $id from ${layer.source}: format: expected $FORMAT")
                    supported
                }
                if (valid.isNotEmpty())
                    accepted[id] = valid.map { layer ->
                        @Suppress("UNCHECKED_CAST") layer.copy(document = copyOf(layer.document) as Map<String, Any?>)
                    }
            }
            return ThemeCatalog(accepted, warn)
        }

        // The catalog keeps its own copy, so a caller's later changes to a document never reach it.
        private fun copyOf(value: Any?): Any? =
            when (value) {
                is Map<*, *> -> value.entries.associate { (key, child) -> key to copyOf(child) }
                is List<*> -> value.map(::copyOf)
                else -> value
            }
    }
}

/**
 * The theme files of the current resources. A host provides a new catalog after each resource reload, which recomposes
 * only the content that reads it and keeps everything it remembers.
 */
val LocalThemeCatalog = compositionLocalOf { ThemeCatalog.Empty }

/** This section's value for the theme [id] in the current resources. */
@Composable
@ReadOnlyComposable
fun <T : Any> ThemeSection<T>.current(id: ThemeId): T = LocalThemeCatalog.current[id, this]
