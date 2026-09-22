package dev.composemc.forge

/** Visible entries are pinned. Capacity pressure never causes redraw/eviction thrashing. */
internal class VisibleIconCache<K, V>(
    private val capacity: Int,
    private val retire: (K, V) -> Unit,
) : AutoCloseable {
    init { require(capacity > 0) }
    private val entries = java.util.LinkedHashMap<K, V>(16, 0.75f, true)
    var visible: Set<K> = emptySet()
    val size: Int get() = entries.size
    operator fun get(key: K): V? = entries[key]
    fun canStore(key: K): Boolean = key in entries || entries.size < capacity || entries.keys.any { it !in visible }
    fun put(key: K, value: V) {
        check(canStore(key)) { "All cached icons are visible; increase the cache capacity" }
        if (key !in entries && entries.size == capacity) {
            val victim = entries.keys.first { it !in visible }
            retire(victim, entries.remove(victim)!!)
        }
        entries.put(key, value)?.let { retire(key, it) }
    }
    override fun close() {
        val old = entries.toMap()
        entries.clear()
        old.forEach(retire)
    }
}
