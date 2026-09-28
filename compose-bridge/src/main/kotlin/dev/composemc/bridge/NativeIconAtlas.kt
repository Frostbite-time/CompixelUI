package dev.composemc.bridge

import kotlin.math.ceil
import kotlin.math.sqrt
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/** A published native image and the pixel region that belongs to one icon. */
class NativeImageRegion(val image: Image, val source: Rect)

/** A resolved redraw policy. Adapters resolve automatic policies before an icon is scheduled. */
class NativeIconRefresh private constructor(val kind: Kind, val intervalMillis: Long) {
    enum class Kind {
        STATIC,
        GAME_TICK,
        FRAME,
        INTERVAL,
        ON_CHANGE,
    }

    companion object {
        val STATIC = NativeIconRefresh(Kind.STATIC, 0)
        val GAME_TICK = NativeIconRefresh(Kind.GAME_TICK, 0)
        val FRAME = NativeIconRefresh(Kind.FRAME, 0)
        /** Redraws when [NativeIconAtlas.Host.appearance] changes, comparing it once per game tick. */
        val ON_CHANGE = NativeIconRefresh(Kind.ON_CHANGE, 0)

        fun every(millis: Long): NativeIconRefresh {
            require(millis > 0) { "Icon refresh interval must be positive" }
            return NativeIconRefresh(Kind.INTERVAL, millis)
        }
    }
}

/**
 * Schedules native icons into small atlas pages; the [Host] owns every Minecraft and GPU operation.
 *
 * Slots remain stable while their icons are cached. Each [prepare] redraws at most one page of up to
 * `preparationsPerFrame` icons: the page whose visible, due icon has waited longest. One snapshot then replaces that
 * page's image. A deferred host publishes a page on a later frame, once its copy is complete, and alternates two
 * buffers. Call every member on the render thread; publication runs on the Compose thread.
 */
class NativeIconAtlas<I : Any>(
    cacheCapacity: Int,
    preparationsPerFrame: Int,
    imageSize: Int,
    private val host: Host<I>,
) : AutoCloseable {
    interface Host<I : Any> {
        /** True when a snapshot can be published in the frame that drew it. */
        val immediate: Boolean

        fun id(icon: I): Long

        /** Resolves the redraw policy once, when [icon] receives a slot. */
        fun refresh(icon: I): NativeIconRefresh

        /**
         * For [NativeIconRefresh.ON_CHANGE] icons: a value that differs, by [Any.equals], whenever drawing [icon] would
         * produce different pixels. Called at most once per game tick for each cached icon.
         */
        fun appearance(icon: I): Any? = null

        /** Clears page [buffer] and draws [icons], mapping the page GUI area onto its pixels. */
        fun draw(buffer: Int, icons: List<Placement<I>>)

        /** Copies page [buffer] into an immutable image; a deferred host returns null until its copy completes. */
        fun snapshot(buffer: Int): Image?

        /**
         * The atlas no longer publishes [image], but pictures Compose recorded earlier may still draw it. A GPU host
         * frees it only after those release it, and on the thread that owns its context.
         */
        fun release(image: Image)

        /** Runs on the Compose thread. */
        fun publish(regions: Map<Long, NativeImageRegion>, removed: Set<Long>)

        /** Runs on the Compose thread and forgets every published region. */
        fun clear()
    }

    /** An icon and its top-left corner in page GUI units. */
    class Placement<I : Any>(val icon: I, val x: Int, val y: Int)

    data class Statistics(
        val activeVariants: Int,
        val cachedImages: Int,
        val pendingImages: Int,
        val preparedImages: Long,
        val retiredImages: Long,
        val dynamicVariants: Int,
        val animationRefreshes: Long,
    )

    private class Entry<I : Any>(val icon: I, val id: Long, val slot: Int, val refresh: NativeIconRefresh) {
        var drawnFrame = 0L
        var drawnAt = 0L
        var tick = 0L
        var guiScale = 0.0
        var hasImage = false
        var appearance: Any? = null
        var appearanceTick = Long.MIN_VALUE
        var drawnAppearance: Any? = null
    }

    private class Page {
        var image: Image? = null
        var published = emptySet<Long>()
    }

    private class Pending<I : Any>(val buffer: Int, val page: Int, val entries: List<Entry<I>>)

    init {
        require(cacheCapacity > 0 && preparationsPerFrame > 0 && imageSize > 0)
    }

    val pageCapacity = minOf(cacheCapacity, preparationsPerFrame)
    private val columns = ceil(sqrt(pageCapacity.toDouble())).toInt()
    private val rows = (pageCapacity + columns - 1) / columns
    /** The GUI area of one page, drawn onto [width] x [height] pixels. */
    val guiWidth = columns * SLOT_UNITS
    val guiHeight = rows * SLOT_UNITS
    val width = ceil(guiWidth * imageSize / 16.0).toInt()
    val height = ceil(guiHeight * imageSize / 16.0).toInt()
    /** Page buffers the host must provide. */
    val buffers = if (host.immediate) 1 else 2

    private val scaleX = width.toFloat() / guiWidth
    private val scaleY = height.toFloat() / guiHeight
    private val pages = Array((cacheCapacity + pageCapacity - 1) / pageCapacity) { Page() }
    private val slots = arrayOfNulls<Entry<I>>(cacheCapacity)
    private val byId = HashMap<Long, Entry<I>>()
    private var visible = LinkedHashMap<Long, I>()
    private var pending: Pending<I>? = null
    private var buffer = -1
    private var frame = 0L
    private var prepared = 0L
    private var retired = 0L
    private var animationRefreshes = 0L
    private var closed = false

    val statistics
        get() =
            Statistics(
                activeVariants = visible.size,
                cachedImages = visible.keys.count { byId[it]?.hasImage == true },
                pendingImages = visible.keys.count { byId[it]?.hasImage != true },
                preparedImages = prepared,
                retiredImages = retired,
                dynamicVariants =
                    visible.keys.count { id ->
                        byId[id]?.let { it.hasImage && it.refresh.kind != NativeIconRefresh.Kind.STATIC } == true
                    },
                animationRefreshes = animationRefreshes,
            )

    /** Replaces the demanded icons, typically after each recorded Compose frame. */
    fun recorded(icons: List<I>) {
        visible = icons.associateByTo(LinkedHashMap()) { host.id(it) }
    }

    /** Prepares at most one page. Returns true when published regions changed. */
    fun prepare(now: Long, tick: Long, guiScale: Double): Boolean {
        check(!closed)
        frame++
        var changed = false
        pending?.let {
            // The pending buffer is not redrawn until its copy completes, possibly several frames later.
            if (!publish(it)) return false
            pending = null
            changed = true
        }
        for ((id, icon) in visible) {
            if (id in byId) continue
            val free = slots.indexOfFirst { it == null }
            val index = if (free >= 0) free else slots.indexOfFirst { it!!.id !in visible }
            if (index < 0) break // All cached slots are pinned by visible icons.
            slots[index]?.let {
                evict(it)
                changed = true
            }
            val entry = Entry(icon, id, index, host.refresh(icon))
            slots[index] = entry
            byId[id] = entry
        }
        val duePage =
            pages.indices
                .filter { page ->
                    pageEntries(page).any { it.id in visible && due(it, now, tick, guiScale) }
                }
                .minByOrNull { page ->
                    pageEntries(page)
                        .filter { it.id in visible && due(it, now, tick, guiScale) }
                        .minOf { it.drawnFrame }
                } ?: return changed

        val entries = pageEntries(duePage)
        buffer = (buffer + 1) % buffers
        host.draw(buffer, entries.map { Placement(it.icon, x(it.slot), y(it.slot)) })
        entries.forEach { entry ->
            if (entry.hasImage && entry.refresh.kind != NativeIconRefresh.Kind.STATIC) animationRefreshes++
            entry.drawnFrame = frame
            entry.drawnAt = now
            entry.tick = tick
            entry.guiScale = guiScale
            if (entry.refresh.kind == NativeIconRefresh.Kind.ON_CHANGE) entry.drawnAppearance = appearance(entry, tick)
        }
        val next = Pending(buffer, duePage, entries)
        if (host.immediate) {
            check(publish(next)) { "An immediate host must copy its page in the frame that drew it" }
            return true
        }
        pending = next
        return changed
    }

    private fun due(entry: Entry<I>, now: Long, tick: Long, guiScale: Double): Boolean {
        if (!entry.hasImage || entry.guiScale != guiScale) return true
        return when (entry.refresh.kind) {
            NativeIconRefresh.Kind.STATIC -> false
            NativeIconRefresh.Kind.GAME_TICK -> entry.tick != tick
            NativeIconRefresh.Kind.FRAME -> entry.drawnFrame != frame
            NativeIconRefresh.Kind.INTERVAL -> now - entry.drawnAt >= entry.refresh.intervalMillis * 1_000_000L
            NativeIconRefresh.Kind.ON_CHANGE -> appearance(entry, tick) != entry.drawnAppearance
        }
    }

    private fun appearance(entry: Entry<I>, tick: Long): Any? {
        if (entry.appearanceTick != tick) {
            entry.appearanceTick = tick
            entry.appearance = host.appearance(entry.icon)
        }
        return entry.appearance
    }

    private fun pageEntries(page: Int): List<Entry<I>> =
        (page * pageCapacity until minOf((page + 1) * pageCapacity, slots.size)).mapNotNull { slots[it] }

    private fun x(slot: Int) = slot % pageCapacity % columns * SLOT_UNITS

    private fun y(slot: Int) = slot % pageCapacity / columns * SLOT_UNITS

    /** False while a deferred host is still copying [result]. */
    private fun publish(result: Pending<I>): Boolean {
        val copy = host.snapshot(result.buffer) ?: return false
        prepared++
        val page = pages[result.page]
        val regions = HashMap<Long, NativeImageRegion>(result.entries.size * 2)
        result.entries.forEach { entry ->
            if (slots[entry.slot] !== entry) return@forEach
            regions[entry.id] =
                NativeImageRegion(
                    copy,
                    Rect.makeXYWH(
                        x(entry.slot) * scaleX,
                        y(entry.slot) * scaleY,
                        ICON_UNITS * scaleX,
                        ICON_UNITS * scaleY,
                    ),
                )
            entry.hasImage = true
        }
        val removed = page.published - regions.keys
        ComposeThread.call { host.publish(regions, removed) }
        page.published = regions.keys
        page.image?.let {
            host.release(it)
            retired++
        }
        page.image = copy
        return true
    }

    private fun evict(entry: Entry<I>) {
        byId.remove(entry.id)
        slots[entry.slot] = null
        val page = pages[entry.slot / pageCapacity]
        if (entry.id in page.published) {
            ComposeThread.call { host.publish(emptyMap(), setOf(entry.id)) }
            page.published = page.published - entry.id
        }
    }

    /** Forgets every slot and releases page images, for example after a resource reload. */
    fun reset() {
        pending = null
        slots.fill(null)
        byId.clear()
        buffer = -1
        ComposeThread.call { host.clear() }
        pages.forEach { page ->
            page.image?.let {
                host.release(it)
                retired++
            }
            page.image = null
            page.published = emptySet()
        }
    }

    override fun close() {
        if (closed) return
        reset()
        visible = LinkedHashMap()
        closed = true
    }

    private companion object {
        /** 16 GUI units per icon plus a gutter for stack counts and shadows. */
        const val SLOT_UNITS = 18
        const val ICON_UNITS = 16f
    }
}
