package dev.compixel.ui.theme

/**
 * One design system's part of a theme file, under the key [name]: `"ore"` holds Ore's colors, and a mod can add a
 * section for its own tokens. A file may hold any number of sections, and sections that nothing reads are ignored.
 *
 * [apply] folds one file into a theme's value: it receives the value so far and the file's value for this section,
 * decoded as maps, lists, strings, numbers, booleans and null, and returns the new value. Reject an invalid file by
 * throwing, for example with `require`; the theme then keeps its value without that file. Sections are compared by
 * identity, so declare each one once, as an object.
 */
abstract class ThemeSection<T : Any>(val name: String) {
    init {
        require(name.matches(Regex("[a-z0-9_.-]+")) && name != "format") { "Invalid theme section name: $name" }
    }

    /** The global default theme's value before any file applies. */
    abstract val default: T

    /** A built-in start for [id], such as light colors for [ThemeId.Light]. Null inherits the usual way. */
    open fun builtIn(id: ThemeId): T? = null

    /** Applies one file's value for this section, [layer], to [value]. */
    abstract fun apply(value: T, layer: Any?): T

    override fun toString(): String = "ThemeSection($name)"
}
