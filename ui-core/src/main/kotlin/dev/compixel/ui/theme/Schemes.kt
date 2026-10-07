package dev.compixel.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap

/** A scheme file's name: `assets/<namespace>/compixel/schemes/<path>.json` in each resource pack. */
@Immutable
data class SchemeId(val namespace: String, val path: String) {
    init {
        require(namespace.matches(ColorSchema.NAME)) { "Invalid scheme namespace: $namespace" }
        require(validPath(path)) { "Invalid scheme path: $path" }
    }

    override fun toString(): String = "$namespace:$path"

    internal companion object {
        private val PATH = Regex("[a-z0-9_./-]+")

        fun validPath(path: String): Boolean =
            path.matches(PATH) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

/**
 * Who chooses a scheme, usually one mod: its screens share [schema] and one list of schemes, the schema's built-in
 * schemes and the files in `assets/<namespace>/compixel/schemes/`. [default] is used until a resource pack or the
 * player chooses another.
 */
@Immutable
data class SchemeOwner(val namespace: String, val schema: ColorSchema, val default: String = "default") {
    init {
        require(namespace.matches(ColorSchema.NAME)) { "Invalid scheme namespace: $namespace" }
        require(SchemeId.validPath(default)) { "Invalid scheme path: $default" }
    }
}

/** A scheme file or an owner's index from one resource pack: its decoded JSON object and, for messages, its source. */
@Immutable data class SchemeFile(val source: String, val document: Map<String, Any?>)

/**
 * The scheme files of the current resources: each scheme `assets/<owner>/compixel/schemes/<path>.json` and each owner's
 * index `assets/<owner>/compixel/schemes.json`, with one file per resource pack, lowest priority first. A catalog never
 * changes; hosts build a new one after each resource reload.
 *
 * A scheme starts from the scheme it `extends`, if any, then its built-in colors, then its files in priority order. In
 * an index, the highest file that sets `default` or `order` decides it. A file whose `format` is not [FORMAT] is
 * skipped; any other problem skips only the field or color it concerns. Each is reported once through the warning
 * callback.
 */
@Immutable
class SchemeCatalog
private constructor(
    private val schemes: Map<SchemeId, List<SchemeFile>>,
    private val indexes: Map<String, List<SchemeFile>>,
    private val warn: (String) -> Unit,
) {
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val ordered = ConcurrentHashMap<SchemeOwner, List<String>>()
    private val parsed = ConcurrentHashMap<Pair<SchemeOwner, String>, List<Map<ColorRole, Color>>>()

    /**
     * The owner's schemes as players see them: its index's `order`, then the owner's default, built-in schemes and the
     * rest by path. The owner's default always exists: without a built-in scheme or file of its own, it has the
     * schema's default colors.
     */
    fun paths(owner: SchemeOwner): List<String> =
        ordered.getOrPut(owner) {
            val builtIn = owner.schema.builtIn.map { it.path }
            val files = schemes.keys.filter { it.namespace == owner.namespace }.map { it.path }.sorted()
            val known = (listOf(owner.default) + builtIn + files).distinct()
            val order =
                index(owner.namespace, "order") { value -> (value as? List<*>)?.takeIf { it.all { it is String } } }
            val listed = order.orEmpty().filterIsInstance<String>().filter { it in known }.distinct()
            listed + known.filter { it !in listed }
        }

    /** The scheme used until the player chooses: the index's `default`, otherwise the owner's own. */
    fun default(owner: SchemeOwner): String =
        index(owner.namespace, "default") { it as? String }?.takeIf { it in paths(owner) } ?: owner.default

    /**
     * What players call the scheme: its highest file's `name`, or a built-in scheme's label; both may be translation
     * keys.
     */
    fun name(owner: SchemeOwner, path: String): String? =
        schemes[SchemeId(owner.namespace, path)].orEmpty().asReversed().firstNotNullOfOrNull {
            it.document["name"] as? String
        } ?: owner.schema.builtIn.find { it.path == path }?.label

    /** The layers of [path], lowest first: what it extends, its built-in colors, then its files. */
    internal fun layers(owner: SchemeOwner, path: String): List<Map<ColorRole, Color>> =
        parsed.getOrPut(owner to path) { layers(owner, path, emptySet()) }

    private fun layers(owner: SchemeOwner, path: String, visiting: Set<String>): List<Map<ColorRole, Color>> {
        val id = SchemeId(owner.namespace, path)
        val files = schemes[id].orEmpty()
        val base =
            files
                .asReversed()
                .firstOrNull { "extends" in it.document }
                ?.let { file ->
                    val extended = file.document["extends"] as? String
                    when {
                        extended == null || extended !in paths(owner) -> {
                            report("Ignoring extends of ${file.source}: no such scheme in ${owner.namespace}")
                            emptyList()
                        }
                        extended == path || extended in visiting -> {
                            report("Ignoring extends of ${file.source}: $id extends itself")
                            emptyList()
                        }
                        else -> layers(owner, extended, visiting + path)
                    }
                } ?: emptyList()
        val builtIn = owner.schema.builtIn.find { it.path == path }?.colors
        return base + listOfNotNull(builtIn) + files.map { colors(owner, it) }
    }

    private fun colors(owner: SchemeOwner, file: SchemeFile): Map<ColorRole, Color> {
        val section = file.document["colors"] ?: return emptyMap()
        if (section !is Map<*, *>) {
            report("Ignoring colors of ${file.source}: expected an object")
            return emptyMap()
        }
        val colors = LinkedHashMap<ColorRole, Color>()
        for ((key, value) in section) {
            val role = (key as? String)?.let(owner.schema::role)
            val color = (value as? String)?.let(ColorHex::parse)
            when {
                role == null -> report("Ignoring color $key of ${file.source}: ${owner.schema.name} has no such color")
                color == null -> report("Ignoring color $key of ${file.source}: expected #RRGGBB or #RRGGBBAA")
                else -> colors[role] = color
            }
        }
        return colors
    }

    private fun <T> index(namespace: String, field: String, read: (Any?) -> T?): T? {
        for (file in indexes[namespace].orEmpty().asReversed()) {
            if (field !in file.document) continue
            read(file.document[field])?.let {
                return it
            }
            report("Ignoring $field of ${file.source}: unexpected value")
        }
        return null
    }

    private fun report(message: String) {
        if (reported.add(message)) warn(message)
    }

    override fun equals(other: Any?): Boolean =
        other is SchemeCatalog && schemes == other.schemes && indexes == other.indexes

    override fun hashCode(): Int = 31 * schemes.hashCode() + indexes.hashCode()

    companion object {
        /** The format of scheme files and indexes, which each states in its `format` field. */
        const val FORMAT = 1

        /** No scheme files: every owner offers its schema's built-in schemes. */
        val Empty = SchemeCatalog(emptyMap(), emptyMap()) {}

        private val SCHEME_FIELDS = setOf("format", "name", "extends", "colors")
        private val INDEX_FIELDS = setOf("format", "default", "order")

        /**
         * A catalog of [schemes] and owner [indexes], each list lowest priority first. Files with another `format` are
         * left out; they and unknown fields are reported through [warn], which also receives the problems found later,
         * on the thread that reads them.
         */
        fun create(
            schemes: Map<SchemeId, List<SchemeFile>>,
            indexes: Map<String, List<SchemeFile>>,
            warn: (String) -> Unit = {},
        ): SchemeCatalog {
            fun accept(files: List<SchemeFile>, fields: Set<String>): List<SchemeFile> = files.mapNotNull { file ->
                val format = file.document["format"]
                if (!(format is Number && format.toDouble() == FORMAT.toDouble())) {
                    warn("Ignoring ${file.source}: format: expected $FORMAT")
                    return@mapNotNull null
                }
                for (field in file.document.keys - fields) warn("Ignoring $field of ${file.source}: unknown field")
                @Suppress("UNCHECKED_CAST") file.copy(document = copyOf(file.document) as Map<String, Any?>)
            }
            return SchemeCatalog(
                schemes.mapValues { accept(it.value, SCHEME_FIELDS) }.filterValues { it.isNotEmpty() },
                indexes.mapValues { accept(it.value, INDEX_FIELDS) }.filterValues { it.isNotEmpty() },
                warn,
            )
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
 * The player's choices: for each owner namespace, the chosen scheme, or null for the default, and the colors changed in
 * each scheme, by scheme path and color key.
 */
@Immutable
data class SchemeSettings(val owners: Map<String, Choice> = emptyMap()) {
    @Immutable data class Choice(val scheme: String? = null, val edits: Map<String, Map<String, Color>> = emptyMap())

    fun choice(owner: SchemeOwner): Choice = owners[owner.namespace] ?: Choice()

    fun with(owner: SchemeOwner, choice: Choice): SchemeSettings =
        copy(owners = if (choice == Choice()) owners - owner.namespace else owners + (owner.namespace to choice))

    /** The settings as their file stores them. */
    fun encode(): Map<String, Any?> =
        mapOf(
            "format" to FORMAT,
            "owners" to
                owners.mapValues { (_, choice) ->
                    buildMap {
                        choice.scheme?.let { put("scheme", it) }
                        put(
                            "edits",
                            choice.edits.mapValues { (_, colors) -> colors.mapValues { ColorHex.format(it.value) } },
                        )
                    }
                },
        )

    companion object {
        const val FORMAT = 1

        /** Reads settings that [encode] wrote. A part that cannot be read is reported through [warn] and left out. */
        fun decode(document: Map<String, Any?>, warn: (String) -> Unit): SchemeSettings {
            val format = document["format"]
            if (!(format is Number && format.toDouble() == FORMAT.toDouble())) {
                warn("Ignoring color settings: format: expected $FORMAT")
                return SchemeSettings()
            }
            val owners = LinkedHashMap<String, Choice>()
            for ((namespace, value) in document["owners"] as? Map<*, *> ?: emptyMap<Any?, Any?>()) {
                if (namespace !is String || value !is Map<*, *>) {
                    warn("Ignoring color settings of $namespace: expected an object")
                    continue
                }
                val scheme = value["scheme"] as? String
                val edits = LinkedHashMap<String, Map<String, Color>>()
                for ((path, colors) in value["edits"] as? Map<*, *> ?: emptyMap<Any?, Any?>()) {
                    if (path !is String || colors !is Map<*, *>) continue
                    val parsed = LinkedHashMap<String, Color>()
                    for ((key, hex) in colors) {
                        val color = (hex as? String)?.let(ColorHex::parse)
                        if (key is String && color != null) parsed[key] = color
                        else warn("Ignoring color $key of $namespace:$path in color settings")
                    }
                    if (parsed.isNotEmpty()) edits[path] = parsed
                }
                owners[namespace] = Choice(scheme, edits)
            }
            return SchemeSettings(owners)
        }
    }
}

/** What screens color themselves with: the scheme files of the current resources and the player's choices. */
@Immutable
class Schemes(val catalog: SchemeCatalog, val settings: SchemeSettings) {
    private val resolved = ConcurrentHashMap<Pair<SchemeOwner, String>, ColorValues>()

    fun paths(owner: SchemeOwner): List<String> = catalog.paths(owner)

    fun default(owner: SchemeOwner): String = catalog.default(owner)

    /** The player's scheme, or the default when the player chose none or chose one that no longer exists. */
    fun selected(owner: SchemeOwner): String =
        settings.choice(owner).scheme?.takeIf { it in paths(owner) } ?: default(owner)

    fun name(owner: SchemeOwner, path: String): String? = catalog.name(owner, path)

    /** The colors the player changed in [path]. */
    fun edits(owner: SchemeOwner, path: String): Map<ColorRole, Color> =
        settings
            .choice(owner)
            .edits[path]
            .orEmpty()
            .entries
            .mapNotNull { (key, color) ->
                owner.schema.role(key)?.let { it to color }
            }
            .toMap()

    /** The colors of [path] with the player's changes on top. */
    fun colors(owner: SchemeOwner, path: String = selected(owner)): ColorValues =
        resolved.getOrPut(owner to path) {
            owner.schema.resolve(catalog.layers(owner, path) + listOf(edits(owner, path)))
        }

    /**
     * A resource pack's files that carry the player's colors of [path], by path inside the pack: an override of [path],
     * or with [newPath] a new scheme named [name] that extends it. [makeDefault] also makes the exported scheme the
     * owner's default. The host adds the pack's own metadata.
     */
    fun export(
        owner: SchemeOwner,
        path: String,
        newPath: String? = null,
        name: String? = null,
        makeDefault: Boolean = false,
    ): Map<String, Map<String, Any?>> {
        require(newPath == null || SchemeId.validPath(newPath)) { "Invalid scheme path: $newPath" }
        val target = newPath ?: path
        val directory = "assets/${owner.namespace}/compixel"
        val colors = settings.choice(owner).edits[path].orEmpty().mapValues { ColorHex.format(it.value) }
        val scheme = buildMap {
            put("format", SchemeCatalog.FORMAT)
            if (newPath != null) {
                name?.let { put("name", it) }
                put("extends", path)
            }
            put("colors", colors)
        }
        return buildMap {
            put("$directory/schemes/$target.json", scheme)
            if (makeDefault)
                put("$directory/schemes.json", mapOf("format" to SchemeCatalog.FORMAT, "default" to target))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Schemes && catalog == other.catalog && settings == other.settings

    override fun hashCode(): Int = 31 * catalog.hashCode() + settings.hashCode()

    companion object {
        val Empty = Schemes(SchemeCatalog.Empty, SchemeSettings())
    }
}

/**
 * The scheme files of the current resources and the player's choices. Hosts provide a new value when either changes,
 * which recomposes only the content that reads it and keeps everything it remembers.
 */
val LocalSchemes = compositionLocalOf { Schemes.Empty }

/** The owner's colors in the scheme the player chose. */
@Composable @ReadOnlyComposable fun SchemeOwner.colors(): ColorValues = LocalSchemes.current.colors(this)

/** Changes the player's choices, as the color editor does. Any thread; screens show a change from their next frame. */
interface SchemeEditing {
    /** Chooses [path] for [owner]; null follows the default again. */
    fun select(owner: SchemeOwner, path: String?)

    /** Sets [role] in [path] to [color], or with null restores the scheme's own color. */
    fun edit(owner: SchemeOwner, path: String, role: ColorRole, color: Color?)

    /** Restores every color the player changed in [path]. */
    fun restore(owner: SchemeOwner, path: String)
}

/** Opens the color editor for the host's design, over the host; null where none can open, such as in HUD layers. */
val LocalColorEditor = staticCompositionLocalOf<(() -> Unit)?> { null }
