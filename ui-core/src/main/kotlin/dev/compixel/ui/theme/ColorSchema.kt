package dev.compixel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * A design system's colors. Each is declared once, in the order an editor lists them: a base color with its default, or
 * a derived color that follows other colors through a rule, such as a hover color a step darker than its button.
 * Declare a schema as an object whose properties hold its colors:
 * ```
 * object ExampleColors : ColorSchema("example") {
 *     val panel = color("panel", "surfaces", Color(0xFF202020))
 *     val accent = color("accent", "accent", Color(0xFF3C8527))
 *     val accentHover = derived("accentHover", "accent", accent) { lerp(it[accent], Color.Black, .16f) }
 * }
 * ```
 *
 * A scheme may also set a derived color. It keeps that color until a higher layer, such as a resource pack or the
 * player, changes a color it follows; it then follows its rule again.
 */
abstract class ColorSchema(val name: String) {
    private val declared = ArrayList<ColorRole>()

    init {
        require(name.matches(NAME)) { "Invalid color schema name: $name" }
    }

    /** Every color in declaration order. */
    val roles: List<ColorRole>
        get() = declared

    private val byKey by lazy { declared.associateBy { it.key } }

    /** The color whose key is [key], if this schema has one. */
    fun role(key: String): ColorRole? = byKey[key]

    /** Schemes every owner of this schema offers before its own scheme files, such as light and dark looks. */
    open val builtIn: List<BuiltInScheme> = emptyList()

    /** The colors when nothing sets any. */
    val defaults: ColorValues by lazy { resolve(emptyList()) }

    protected fun color(key: String, group: String, default: Color): ColorRole =
        add(key, group, default, emptyList(), null)

    /**
     * A color that follows [follows] through [rule], which may read only those colors. [default] is this schema's own
     * color for it, kept until something changes a color it follows.
     */
    protected fun derived(
        key: String,
        group: String,
        vararg follows: ColorRole,
        default: Color? = null,
        rule: (ColorValues) -> Color,
    ): ColorRole {
        require(follows.isNotEmpty()) { "$key: a derived color follows at least one color" }
        require(follows.all { it.schema === this }) { "$key: derived from another schema's colors" }
        return add(key, group, default, follows.toList(), rule)
    }

    /** [defaults] with [colors] set exactly. A derived color that is not set follows the colors it derives from. */
    fun values(vararg colors: Pair<ColorRole, Color>): ColorValues {
        require(colors.all { it.first.schema === this }) { "Colors of another schema" }
        return resolve(listOf(colors.toMap()))
    }

    /** The colors of the built-in scheme at [path]. */
    fun scheme(path: String): ColorValues =
        resolve(listOf(requireNotNull(builtIn.find { it.path == path }) { "No built-in scheme $path in $name" }.colors))

    private fun add(
        key: String,
        group: String,
        default: Color?,
        follows: List<ColorRole>,
        rule: ((ColorValues) -> Color)?,
    ): ColorRole {
        require(key.matches(KEY) && group.matches(KEY)) { "Invalid color key or group: $key, $group" }
        require(declared.none { it.key == key }) { "Duplicate color $key in $name" }
        return ColorRole(this, key, group, declared.size, default, follows, rule).also(declared::add)
    }

    /**
     * Applies [layers], lowest first, over the defaults. A derived color set in a layer keeps that color unless a color
     * it follows was set in a higher layer; otherwise, and when nothing sets it, it follows its rule.
     */
    internal fun resolve(layers: List<Map<ColorRole, Color>>): ColorValues {
        val colors = LongArray(declared.size)
        val level = IntArray(declared.size) { UNSET }
        for (role in declared) role.default?.let {
            colors[role.index] = it.packed
            level[role.index] = -1
        }
        layers.forEachIndexed { index, layer ->
            for ((role, color) in layer) {
                colors[role.index] = color.packed
                level[role.index] = index
            }
        }
        val following = BooleanArray(declared.size)
        // Rules read the colors resolved so far; the colors they follow are declared, and so resolved, before them.
        val resolved = ColorValues(this, colors, following)
        for (role in declared) {
            val rule = role.rule ?: continue
            val followed = role.follows.maxOf { level[it.index] }
            if (level[role.index] == UNSET || followed > level[role.index]) {
                colors[role.index] = rule(resolved).packed
                level[role.index] = followed
                following[role.index] = true
            }
        }
        return ColorValues(this, colors.copyOf(), following.copyOf())
    }

    override fun toString(): String = "ColorSchema($name)"

    internal companion object {
        val NAME = Regex("[a-z0-9_.-]+")
        val KEY = Regex("[a-zA-Z][a-zA-Z0-9]*")
        private const val UNSET = Int.MIN_VALUE
    }
}

/**
 * One color of a [ColorSchema]: its [key] in scheme files, the [group] an editor lists it under, and what it follows.
 */
class ColorRole
internal constructor(
    val schema: ColorSchema,
    val key: String,
    val group: String,
    internal val index: Int,
    internal val default: Color?,
    /** The colors a derived color follows; empty for a base color. */
    val follows: List<ColorRole>,
    internal val rule: ((ColorValues) -> Color)?,
) {
    val derived: Boolean
        get() = rule != null

    override fun toString(): String = "${schema.name}.$key"
}

/** One scheme's colors for every role of [schema]. */
@Immutable
class ColorValues
internal constructor(val schema: ColorSchema, private val colors: LongArray, private val following: BooleanArray) {
    operator fun get(role: ColorRole): Color {
        require(role.schema === schema) { "$role is not a color of $schema" }
        return Color(colors[role.index].toULong())
    }

    /** True when [role] is derived and follows its rule here rather than a color of its own. */
    fun follows(role: ColorRole): Boolean {
        require(role.schema === schema) { "$role is not a color of $schema" }
        return following[role.index]
    }

    override fun equals(other: Any?): Boolean =
        other is ColorValues &&
            other.schema === schema &&
            other.colors.contentEquals(colors) &&
            other.following.contentEquals(following)

    override fun hashCode(): Int = 31 * colors.contentHashCode() + following.contentHashCode()
}

/** A scheme every owner of a schema offers: its [path] in the owner, a translation key [label] and its [colors]. */
@Immutable
class BuiltInScheme(val path: String, val label: String, val colors: Map<ColorRole, Color> = emptyMap()) {
    init {
        require(SchemeId.validPath(path)) { "Invalid scheme path: $path" }
    }
}

/** Colors as scheme files write them: `#RRGGBB`, or `#RRGGBBAA` with alpha. */
object ColorHex {
    private val HEX = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

    /** The color [text] writes, or null when it is not `#RRGGBB` or `#RRGGBBAA`. */
    fun parse(text: String): Color? {
        if (!text.matches(HEX)) return null
        val bits = text.drop(1).toLong(16)
        val argb = if (text.length == 7) bits or 0xFF000000L else (bits ushr 8) or ((bits and 255) shl 24)
        return Color(argb.toInt())
    }

    /** `#RRGGBB` when [color] is opaque, otherwise `#RRGGBBAA`. */
    fun format(color: Color): String {
        val argb = color.toArgb()
        val rgb = "%06X".format(argb and 0xFFFFFF)
        val alpha = argb ushr 24
        return if (alpha == 255) "#$rgb" else "#$rgb" + "%02X".format(alpha)
    }
}

private val Color.packed: Long
    get() = value.toLong()
