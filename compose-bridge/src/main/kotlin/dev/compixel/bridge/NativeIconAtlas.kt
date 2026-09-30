package dev.compixel.bridge

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.jetbrains.skia.IRect
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
 * Schedules native icons into atlas pages; the [Host] owns every Minecraft and GPU operation.
 *
 * Every demanded icon gets a slot, and pages are added as demand grows. The atlas keeps `cacheCapacity` icons, or every
 * demanded icon when more are demanded: icons no longer demanded stay cached while they fit, so they return without
 * drawing, and otherwise the least recently demanded give up their slots. A page without icons is discarded. Slots
 * remain stable while their icons are cached.
 *
 * The host keeps every page's pixels. Each [prepare] draws only the due icons of one page into their own cells, at most
 * `preparationsPerFrame` of them, choosing the page whose due icon has waited longest. One snapshot then replaces that
 * page's image: its other icons move to the snapshot with the same pixels, and only the drawn ones are reported as
 * changed. A deferred host publishes a page on a later frame, once its copy is complete, and alternates two buffers.
 *
 * Pages are drawn at the pixel size [prepare] asks for, normally the on-screen size of a 16 dp icon, so that icon is
 * sampled pixel for pixel. A page drawn at another size is redrawn completely at the new one, one page per frame, and
 * its icons keep their previous image until then; its hidden icons give up their pixels until they are demanded again.
 * Call every member on the render thread; publication runs on the Compose thread.
 */
class NativeIconAtlas<I : Any>(
    private val cacheCapacity: Int,
    preparationsPerFrame: Int,
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

        /**
         * Draws [icons] into [page], mapping the page GUI area onto [width] x [height] pixels. Only their cells
         * ([cell]) are cleared first; the rest of the page keeps its pixels. A page drawn for the first time, again
         * after [discard], or at another size than its previous draw starts transparent. A deferred host also starts
         * copying the page for [snapshot] through [buffer].
         */
        fun draw(page: Int, buffer: Int, icons: List<Placement<I>>)

        /** Copies [page] into an immutable image; a deferred host returns null until its copy through [buffer] ends. */
        fun snapshot(page: Int, buffer: Int): Image?

        /**
         * The atlas no longer publishes [image], but pictures Compose recorded earlier may still draw it. A GPU host
         * frees it only after those release it, and on the thread that owns its context.
         */
        fun release(image: Image)

        /** [page] holds no icons any more; free its pixels. */
        fun discard(page: Int)

        /**
         * Runs on the Compose thread. [regions] replace the regions of their icons. Only the icons in [changed] have
         * different pixels; the others moved to a newer image with the same pixels, so drawing recorded from their
         * previous region stays correct.
         */
        fun publish(regions: Map<Long, NativeImageRegion>, changed: Set<Long>, removed: Set<Long>)

        /** Runs on the Compose thread and forgets every published region. */
        fun clear()
    }

    /** An icon and the top-left corner of its [SLOT_UNITS]-square cell in page GUI units. */
    class Placement<I : Any>(val icon: I, val x: Int, val y: Int)

    data class Statistics(
        val activeVariants: Int,
        val cachedImages: Int,
        val pendingImages: Int,
        val preparedImages: Long,
        val retiredImages: Long,
        val dynamicVariants: Int,
        val animationRefreshes: Long,
        /** Pages currently holding icons. */
        val pages: Int,
        /** Icons drawn into their cells since the atlas opened. */
        val drawnIcons: Long,
    )

    private class Entry<I : Any>(
        val icon: I,
        val id: Long,
        val page: Int,
        val slot: Int,
        val refresh: NativeIconRefresh,
    ) {
        var demandedFrame = 0L
        var drawnFrame = 0L
        var drawnAt = 0L
        var tick = 0L
        var guiScale = 0.0
        var hasImage = false
        var appearance: Any? = null
        var appearanceTick = Long.MIN_VALUE
        var drawnAppearance: Any? = null
    }

    private class Page<I : Any>(capacity: Int) {
        val slots = arrayOfNulls<Entry<I>>(capacity)
        var used = 0
        var image: Image? = null
        /** The image size its pixels were drawn at; 0 before its first draw. */
        var imageSize = 0
    }

    private class Pending<I : Any>(val buffer: Int, val page: Int, val entries: List<Entry<I>>)

    init {
        require(cacheCapacity >= 0 && preparationsPerFrame > 0)
    }

    /** Slots per page. A page never holds more icons than one frame may draw. */
    val pageCapacity = preparationsPerFrame
    private val columns = ceil(sqrt(pageCapacity.toDouble())).toInt()
    private val rows = (pageCapacity + columns - 1) / columns
    /** The GUI area of one page, drawn onto [width] x [height] pixels. */
    val guiWidth = columns * SLOT_UNITS
    val guiHeight = rows * SLOT_UNITS
    /** Pixels drawn for an icon's 16 GUI units, as the latest [prepare] asked. */
    var imageSize = 0
        private set

    /** A page's pixels at the current [imageSize]. */
    val width
        get() = pixels(guiWidth, imageSize)

    val height
        get() = pixels(guiHeight, imageSize)

    /** Copy buffers a deferred host must provide. */
    val buffers = if (host.immediate) 1 else 2

    private val pages = ArrayList<Page<I>?>()
    private val byId = HashMap<Long, Entry<I>>()
    private var visible = LinkedHashMap<Long, I>()
    private var pending: Pending<I>? = null
    private var buffer = -1
    private var frame = 0L
    private var prepared = 0L
    private var retired = 0L
    private var animationRefreshes = 0L
    private var drawnIcons = 0L
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
                pages = pages.count { it != null },
                drawnIcons = drawnIcons,
            )

    /**
     * The page pixels of [placement]'s cell, with a top-left origin. Neighbouring cells share their edges, so clearing
     * one never touches another.
     */
    fun cell(placement: Placement<*>): IRect {
        val scaleX = width.toFloat() / guiWidth
        val scaleY = height.toFloat() / guiHeight
        return IRect.makeLTRB(
            (placement.x * scaleX).roundToInt(),
            (placement.y * scaleY).roundToInt(),
            ((placement.x + SLOT_UNITS) * scaleX).roundToInt(),
            ((placement.y + SLOT_UNITS) * scaleY).roundToInt(),
        )
    }

    /** Replaces the demanded icons, typically after each recorded Compose frame. */
    fun recorded(icons: List<I>) {
        visible = icons.associateByTo(LinkedHashMap()) { host.id(it) }
    }

    /**
     * Draws the due icons of at most one page, [imageSize] pixels for each icon's 16 GUI units. Returns true when
     * published regions changed.
     */
    fun prepare(now: Long, tick: Long, guiScale: Double, imageSize: Int): Boolean {
        check(!closed)
        require(imageSize in IMAGE_SIZES)
        frame++
        var changed = false
        pending?.let {
            // The pending page is not redrawn until its copy completes, possibly several frames later.
            if (!publish(it)) return false
            pending = null
            changed = true
        }
        this.imageSize = imageSize
        for (id in visible.keys) byId[id]?.demandedFrame = frame
        val removed = HashSet<Long>()
        trim(removed)
        for ((id, icon) in visible) if (id !in byId) place(id, icon, removed)
        discardEmptyPages()
        val index = duePage(now, tick, guiScale)
        val page = index?.let { checkNotNull(pages[it]) }
        val entries =
            page?.slots?.filterNotNull()?.filter { it.id in visible && due(it, now, tick, guiScale) }.orEmpty()
        if (page != null && page.imageSize != imageSize) {
            // The page starts over at the new size: icons not drawn now lose their pixels until they are due again.
            val drawn = entries.mapTo(HashSet()) { it.id }
            page.slots.forEach { entry ->
                if (entry != null && entry.hasImage && entry.id !in drawn) {
                    entry.hasImage = false
                    removed += entry.id
                }
            }
            page.imageSize = imageSize
        }
        if (removed.isNotEmpty()) {
            ComposeThread.call { host.publish(emptyMap(), emptySet(), removed) }
            changed = true
        }
        if (index == null) return changed

        buffer = (buffer + 1) % buffers
        host.draw(index, buffer, entries.map { Placement(it.icon, x(it.slot), y(it.slot)) })
        drawnIcons += entries.size
        entries.forEach { entry ->
            if (entry.hasImage && entry.refresh.kind != NativeIconRefresh.Kind.STATIC) animationRefreshes++
            entry.drawnFrame = frame
            entry.drawnAt = now
            entry.tick = tick
            entry.guiScale = guiScale
            if (entry.refresh.kind == NativeIconRefresh.Kind.ON_CHANGE) entry.drawnAppearance = appearance(entry, tick)
        }
        val next = Pending(buffer, index, entries)
        if (host.immediate) {
            check(publish(next)) { "An immediate host must copy its page in the frame that drew it" }
            return true
        }
        pending = next
        return changed
    }

    /** The page whose due, demanded icon was drawn longest ago. */
    private fun duePage(now: Long, tick: Long, guiScale: Double): Int? {
        var best = -1
        var bestFrame = Long.MAX_VALUE
        pages.forEachIndexed { index, page ->
            page?.slots?.forEach { entry ->
                if (
                    entry != null &&
                        entry.drawnFrame < bestFrame &&
                        entry.id in visible &&
                        due(entry, now, tick, guiScale)
                ) {
                    best = index
                    bestFrame = entry.drawnFrame
                }
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun due(entry: Entry<I>, now: Long, tick: Long, guiScale: Double): Boolean {
        if (!entry.hasImage || entry.guiScale != guiScale || pages[entry.page]?.imageSize != imageSize) return true
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

    /** Evicts the least recently demanded hidden icons beyond the cache, or beyond the demand when that is larger. */
    private fun trim(removed: MutableSet<Long>) {
        val excess = byId.size - maxOf(cacheCapacity, visible.size)
        if (excess <= 0) return
        byId.values
            .filter { it.id !in visible }
            .sortedBy { it.demandedFrame }
            .take(excess)
            .forEach { evict(it, removed) }
    }

    /** A free slot first, then a hidden icon's once the cache is full, and otherwise a new page. */
    private fun place(id: Long, icon: I, removed: MutableSet<Long>) {
        var index = pages.indexOfFirst { it != null && it.used < pageCapacity }
        if (index < 0 && byId.size >= maxOf(cacheCapacity, visible.size)) {
            byId.values
                .filter { it.id !in visible }
                .minByOrNull { it.demandedFrame }
                ?.let {
                    evict(it, removed)
                    index = it.page
                }
        }
        if (index < 0) {
            index = pages.indexOfFirst { it == null }
            if (index < 0) {
                index = pages.size
                pages += null
            }
            pages[index] = Page(pageCapacity)
        }
        val page = checkNotNull(pages[index])
        val slot = page.slots.indexOfFirst { it == null }
        val entry = Entry(icon, id, index, slot, host.refresh(icon)).also { it.demandedFrame = frame }
        page.slots[slot] = entry
        page.used++
        byId[id] = entry
    }

    private fun evict(entry: Entry<I>, removed: MutableSet<Long>) {
        byId.remove(entry.id)
        val page = checkNotNull(pages[entry.page])
        page.slots[entry.slot] = null
        page.used--
        if (entry.hasImage) removed += entry.id
    }

    private fun discardEmptyPages() {
        pages.forEachIndexed { index, page ->
            if (page == null || page.used > 0) return@forEachIndexed
            page.image?.let {
                host.release(it)
                retired++
            }
            pages[index] = null
            host.discard(index)
        }
        while (pages.isNotEmpty() && pages.last() == null) pages.removeAt(pages.lastIndex)
    }

    private fun x(slot: Int) = slot % columns * SLOT_UNITS

    private fun y(slot: Int) = slot / columns * SLOT_UNITS

    /** False while a deferred host is still copying [result]. */
    private fun publish(result: Pending<I>): Boolean {
        val page = checkNotNull(pages[result.page]) { "A page with a pending copy was discarded" }
        val copy = host.snapshot(result.page, result.buffer) ?: return false
        prepared++
        val changed = HashSet<Long>()
        result.entries.forEach { entry ->
            if (page.slots[entry.slot] !== entry) return@forEach
            entry.hasImage = true
            changed += entry.id
        }
        // The page kept the pixels of every other icon, so all of them move to the new copy, at the size it was drawn.
        val scaleX = pixels(guiWidth, page.imageSize).toFloat() / guiWidth
        val scaleY = pixels(guiHeight, page.imageSize).toFloat() / guiHeight
        val regions = HashMap<Long, NativeImageRegion>()
        page.slots.forEach { entry ->
            if (entry == null || !entry.hasImage) return@forEach
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
        }
        ComposeThread.call { host.publish(regions, changed, emptySet()) }
        page.image?.let {
            host.release(it)
            retired++
        }
        page.image = copy
        return true
    }

    /** Forgets every slot, releases page images and discards pages, for example after a resource reload. */
    fun reset() {
        pending = null
        byId.clear()
        buffer = -1
        ComposeThread.call { host.clear() }
        pages.forEachIndexed { index, page ->
            if (page == null) return@forEachIndexed
            page.image?.let {
                host.release(it)
                retired++
            }
            host.discard(index)
        }
        pages.clear()
    }

    override fun close() {
        if (closed) return
        reset()
        visible = LinkedHashMap()
        closed = true
    }

    companion object {
        /** GUI units of one cell: 16 for the icon plus a gutter for stack counts and shadows. */
        const val SLOT_UNITS = 18
        private const val ICON_UNITS = 16f
        /** The image sizes an atlas accepts. */
        val IMAGE_SIZES = 16..256

        /**
         * The on-screen pixels of a 16 dp icon at [density], within [IMAGE_SIZES]. Drawn at this size, the icon is
         * sampled pixel for pixel, as the game draws its own items at the GUI scale.
         */
        fun imageSize(density: Float): Int = (16 * density).roundToInt().coerceIn(IMAGE_SIZES)

        private fun pixels(guiUnits: Int, imageSize: Int) = ceil(guiUnits * imageSize / 16.0).toInt()
    }
}
