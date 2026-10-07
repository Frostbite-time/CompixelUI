package dev.compixel.bridge

import dev.compixel.render.NativeImageOwner
import dev.compixel.render.NativeImageStatistics
import kotlin.math.ceil
import kotlin.math.sqrt
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/** A published native image and the pixel region that belongs to one icon. */
class NativeImageRegion(val image: Image, val source: Rect)

/** A resolved redraw policy. Adapters resolve automatic policies before an icon is scheduled. */
class NativeImageRefresh private constructor(val kind: Kind, val intervalMillis: Long) {
    enum class Kind {
        STATIC,
        GAME_TICK,
        FRAME,
        INTERVAL,
        ON_CHANGE,
    }

    /** Shared refresh decision; owners handle request/size/resource invalidation before consulting this policy. */
    fun isDue(
        now: Long,
        drawnAt: Long,
        tick: Long = 0,
        drawnTick: Long = 0,
        frame: Long = 0,
        drawnFrame: Long = 0,
        appearanceChanged: Boolean = false,
    ): Boolean =
        when (kind) {
            Kind.STATIC -> false
            Kind.GAME_TICK -> tick != drawnTick
            Kind.FRAME -> frame != drawnFrame
            Kind.INTERVAL -> now - drawnAt >= intervalMillis * 1_000_000L
            Kind.ON_CHANGE -> appearanceChanged
        }

    companion object {
        val STATIC = NativeImageRefresh(Kind.STATIC, 0)
        val GAME_TICK = NativeImageRefresh(Kind.GAME_TICK, 0)
        val FRAME = NativeImageRefresh(Kind.FRAME, 0)
        /** Redraws when [NativeImageAtlas.Host.appearance] changes, comparing it once per game tick. */
        val ON_CHANGE = NativeImageRefresh(Kind.ON_CHANGE, 0)

        fun every(millis: Long): NativeImageRefresh {
            require(millis > 0) { "Native refresh interval must be positive" }
            return NativeImageRefresh(Kind.INTERVAL, millis)
        }
    }
}

/**
 * Bounded native-image scheduling, caching and publication. Adapters own native drawing and GPU operations. Requests
 * carry physical width and height; the host chooses a grid or an independent target for each size. Only due cells are
 * redrawn, and unchanged regions follow the new page snapshot without invalidating Compose. Visible content is retained
 * regardless of cache capacity, which limits only inactive cached variants. A changed size settles for [SETTLE_FRAMES]
 * frames while its nearest published image remains available. A prepare call draws at most [preparationsPerFrame]
 * icons, on as many pages as they need; a deferred host publishes those pages before any more are drawn. All scheduling
 * and retirement runs on the owner thread; publication runs on the Compose thread.
 */
class NativeImageAtlas<I : Any>(
    private val cacheCapacity: Int,
    private val preparationsPerFrame: Int,
    private val host: Host<I>,
    /** Slots per page: the frame budget, at most [PAGE_CAPACITY] by default. */
    val pageCapacity: Int = minOf(preparationsPerFrame, PAGE_CAPACITY),
) : AutoCloseable {
    /** Exact physical dimensions. Logical/native GUI coordinates belong to the adapter. */
    data class Size(val width: Int, val height: Int) {
        init {
            require(width > 0 && height > 0)
        }
    }

    data class Variant(val id: Long, val size: Size)

    class Request<I : Any>(val icon: I, val size: Size)

    /** Allocation policy: dense grids for icons, one target for content that requires its own viewport. */
    class Layout(val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int, val capacity: Int) {
        init {
            require(columns > 0 && rows > 0 && cellWidth > 0 && cellHeight > 0 && capacity > 0)
            require(capacity.toLong() <= columns.toLong() * rows)
            require(columns.toLong() * cellWidth <= Int.MAX_VALUE && rows.toLong() * cellHeight <= Int.MAX_VALUE)
        }

        companion object {
            fun grid(size: Size, capacity: Int, gutterX: Int = 0, gutterY: Int = 0): Layout {
                require(capacity > 0 && gutterX >= 0 && gutterY >= 0)
                val columns = ceil(sqrt(capacity.toDouble())).toInt()
                return Layout(
                    columns,
                    (capacity + columns - 1) / columns,
                    Math.addExact(size.width, gutterX),
                    Math.addExact(size.height, gutterY),
                    capacity,
                )
            }

            fun single(size: Size) = Layout(1, 1, size.width, size.height, 1)
        }
    }

    interface Host<I : Any> {
        /** True when a snapshot can be published in the frame that drew it. */
        val immediate: Boolean

        fun id(icon: I): Long

        /** Resolves the redraw policy once, when [icon] receives a slot. */
        fun refresh(icon: I): NativeImageRefresh

        fun layout(size: Size, capacity: Int): Layout

        /**
         * For [NativeImageRefresh.ON_CHANGE] icons: a value that differs, by [Any.equals], whenever drawing [icon]
         * would produce different pixels. Called at most once per game tick for each cached icon.
         */
        fun appearance(icon: I): Any? = null

        /**
         * Draws due cells into a persistent page; placements and dimensions are framebuffer pixels. [buffer] alternates
         * between the draws of one page, so a deferred host can copy into one while the other may still be read.
         */
        fun draw(page: Int, buffer: Int, size: Size, width: Int, height: Int, icons: List<Placement<I>>)

        /**
         * Copies the [width] x [height] pixels of [page] into an immutable image; a deferred host returns null until
         * its copy through [buffer] ends.
         */
        fun snapshot(page: Int, buffer: Int, width: Int, height: Int): Image?

        /**
         * The atlas no longer publishes [image], but pictures Compose recorded earlier may still draw it. A GPU host
         * frees it only after those release it, and on the thread that owns its context.
         */
        fun release(image: Image)

        /** [page] holds no icons any more; free its pixels. */
        fun discard(page: Int)

        /**
         * Runs on the Compose thread. [regions] replace the regions of their variants. Only the variants in [changed]
         * have different pixels; the others moved to a newer image with the same pixels, so drawing recorded from their
         * previous region stays correct.
         */
        fun publish(regions: Map<Variant, NativeImageRegion>, changed: Set<Variant>, removed: Set<Variant>)

        /** Runs on the Compose thread and forgets every published region. */
        fun clear()
    }

    /** An image, its top-left pixel origin and its exclusive allocation cell. */
    class Placement<I : Any>(val icon: I, val x: Float, val y: Float, val cell: IRect)

    private class Entry<I : Any>(
        val icon: I,
        val variant: Variant,
        val page: Int,
        val slot: Int,
        val refresh: NativeImageRefresh,
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

    private class Page<I : Any>(val size: Size, val layout: Layout, retire: (Image) -> Unit) {
        val slots = arrayOfNulls<Entry<I>>(layout.capacity)
        var used = 0
        var buffer = -1
        val owner = NativeImageOwner(retire)
    }

    private class Pending<I : Any>(val buffer: Int, val page: Int, val entries: List<Entry<I>>)

    init {
        require(cacheCapacity >= 0 && preparationsPerFrame > 0 && pageCapacity in 1..preparationsPerFrame)
    }

    private val pagesPerFrame = (preparationsPerFrame + pageCapacity - 1) / pageCapacity
    /** Copy buffers a deferred host must provide for each page. */
    val buffers = if (host.immediate) 1 else 2

    private val pages = ArrayList<Page<I>?>()
    private val byVariant = HashMap<Variant, Entry<I>>()
    private var visible = LinkedHashMap<Variant, I>()
    /** New sizes of icons that already have an image, and the frame since which each has been demanded. */
    private val settling = HashMap<Variant, Long>()
    private val pending = ArrayList<Pending<I>>()
    private var frame = 0L
    private var prepared = 0L
    private var retired = 0L
    private var animationRefreshes = 0L
    private var drawnImages = 0L
    private var closed = false

    val statistics
        get() =
            NativeImageStatistics(
                activeVariants = visible.size,
                cachedImages = visible.keys.count { byVariant[it]?.hasImage == true },
                pendingImages = visible.keys.count { byVariant[it]?.hasImage != true },
                preparedImages = prepared,
                retiredImages = retired,
                dynamicVariants =
                    visible.keys.count { variant ->
                        byVariant[variant]?.let { it.hasImage && it.refresh.kind != NativeImageRefresh.Kind.STATIC } ==
                            true
                    },
                animationRefreshes = animationRefreshes,
                pages = pages.count { it != null },
                drawnImages = drawnImages,
            )

    /** Replaces the demanded variants, typically after each recorded Compose frame. */
    fun recorded(requests: List<Request<I>>) {
        visible = requests.associateByTo(LinkedHashMap(), { Variant(host.id(it.icon), it.size) }, { it.icon })
    }

    /** Draws due variants within the image and page budgets. Returns true when published regions changed. */
    fun prepare(now: Long, tick: Long, guiScale: Double): Boolean {
        check(!closed)
        frame++
        var changed = false
        if (pending.isNotEmpty()) {
            // The pending page is not redrawn until its copy completes, possibly several frames later.
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (publish(iterator.next())) {
                    iterator.remove()
                    changed = true
                }
            }
            if (pending.isNotEmpty()) return changed
        }
        if (visible.isEmpty() && byVariant.isEmpty()) return changed
        for (variant in visible.keys) byVariant[variant]?.demandedFrame = frame
        settling.keys.retainAll(visible.keys)
        // Icons still waiting for a demanded size keep their other images, which remain the nearest ones to show.
        val waiting = visible.keys.filter { byVariant[it]?.hasImage != true }.mapTo(HashSet()) { it.id }
        val removed = HashSet<Variant>()
        trim(removed, waiting)
        val drawn = byVariant.values.filter { it.hasImage }.mapTo(HashSet()) { it.variant.id }
        for ((variant, icon) in visible) if (variant !in byVariant && settled(variant, drawn))
            place(variant, icon, removed, waiting)
        discardEmptyPages()
        if (removed.isNotEmpty()) {
            ComposeThread.call { host.publish(emptyMap(), emptySet(), removed) }
            changed = true
        }

        var remaining = preparationsPerFrame
        repeat(pagesPerFrame) {
            val index = duePage(now, tick, guiScale) ?: return changed
            val page = checkNotNull(pages[index])
            val entries =
                page.slots
                    .filterNotNull()
                    .filter { it.variant in visible && due(it, now, tick, guiScale) }
                    .take(remaining)
            if (entries.isEmpty()) return changed
            page.buffer = (page.buffer + 1) % buffers
            val size = page.size
            host.draw(
                index,
                page.buffer,
                size,
                width(page),
                height(page),
                entries.map { entry ->
                    val cell = cell(page, entry.slot)
                    Placement(entry.icon, cell.left.toFloat(), cell.top.toFloat(), cell)
                },
            )
            drawnImages += entries.size
            entries.forEach { entry ->
                if (entry.hasImage && entry.refresh.kind != NativeImageRefresh.Kind.STATIC) animationRefreshes++
                entry.drawnFrame = frame
                entry.drawnAt = now
                entry.tick = tick
                entry.guiScale = guiScale
                if (entry.refresh.kind == NativeImageRefresh.Kind.ON_CHANGE)
                    entry.drawnAppearance = appearance(entry, tick)
            }
            val next = Pending(page.buffer, index, entries)
            if (host.immediate) {
                check(publish(next)) { "An immediate host must copy its page in the frame that drew it" }
                changed = true
            } else {
                pending += next
            }
            remaining -= entries.size
            if (remaining == 0) return changed
        }
        return changed
    }

    /** A variant of an icon with no image yet gets a slot at once; another size of a drawn icon once it has settled. */
    private fun settled(variant: Variant, drawn: Set<Long>): Boolean {
        if (variant.id !in drawn) return true
        val since = settling.getOrPut(variant) { frame }
        if (frame - since < SETTLE_FRAMES) return false
        settling.remove(variant)
        return true
    }

    /** The page whose due, demanded variant was drawn longest ago. */
    private fun duePage(now: Long, tick: Long, guiScale: Double): Int? {
        var best = -1
        var bestFrame = Long.MAX_VALUE
        pages.forEachIndexed { index, page ->
            if (pending.any { it.page == index }) return@forEachIndexed
            page?.slots?.forEach { entry ->
                if (
                    entry != null &&
                        entry.drawnFrame < bestFrame &&
                        entry.variant in visible &&
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
        if (!entry.hasImage || entry.guiScale != guiScale) return true
        return entry.refresh.isDue(
            now,
            entry.drawnAt,
            tick,
            entry.tick,
            frame,
            entry.drawnFrame,
            entry.refresh.kind == NativeImageRefresh.Kind.ON_CHANGE && appearance(entry, tick) != entry.drawnAppearance,
        )
    }

    private fun appearance(entry: Entry<I>, tick: Long): Any? {
        if (entry.appearanceTick != tick) {
            entry.appearanceTick = tick
            entry.appearance = host.appearance(entry.icon)
        }
        return entry.appearance
    }

    /** Hidden variants, except the images of [waiting] icons. */
    private fun evictable(waiting: Set<Long>) =
        byVariant.values.filter { it.variant !in visible && !(it.hasImage && it.variant.id in waiting) }

    /**
     * Evicts the least recently demanded hidden variants beyond the cache, or beyond the demand when that is larger.
     */
    private fun trim(removed: MutableSet<Variant>, waiting: Set<Long>) {
        val excess = byVariant.size - maxOf(cacheCapacity, visible.size)
        if (excess <= 0) return
        evictable(waiting).sortedBy { it.demandedFrame }.take(excess).forEach { evict(it, removed) }
    }

    /**
     * A free slot on a page of the variant's size first. Once the cache is full, the least recently demanded hidden
     * variant gives up its slot, which is reused when its page has the same size. Otherwise a new page.
     */
    private fun place(variant: Variant, icon: I, removed: MutableSet<Variant>, waiting: Set<Long>) {
        var index = pages.indexOfFirst { it != null && it.size == variant.size && it.used < it.layout.capacity }
        if (index < 0 && byVariant.size >= maxOf(cacheCapacity, visible.size)) {
            evictable(waiting)
                .minByOrNull { it.demandedFrame }
                ?.let {
                    evict(it, removed)
                    if (checkNotNull(pages[it.page]).size == variant.size) index = it.page
                }
        }
        if (index < 0) {
            index = pages.indexOfFirst { it == null }
            if (index < 0) {
                index = pages.size
                pages += null
            }
            val layout = host.layout(variant.size, pageCapacity)
            require(
                layout.capacity <= pageCapacity &&
                    layout.cellWidth >= variant.size.width &&
                    layout.cellHeight >= variant.size.height
            )
            pages[index] = Page(variant.size, layout, host::release)
        }
        val page = checkNotNull(pages[index])
        val slot = page.slots.indexOfFirst { it == null }
        val entry = Entry(icon, variant, index, slot, host.refresh(icon)).also { it.demandedFrame = frame }
        page.slots[slot] = entry
        page.used++
        byVariant[variant] = entry
    }

    private fun evict(entry: Entry<I>, removed: MutableSet<Variant>) {
        byVariant.remove(entry.variant)
        val page = checkNotNull(pages[entry.page])
        page.slots[entry.slot] = null
        page.used--
        if (entry.hasImage) removed += entry.variant
    }

    private fun discardEmptyPages() {
        pages.forEachIndexed { index, page ->
            if (page == null || page.used > 0) return@forEachIndexed
            if (page.owner.image != null) {
                page.owner.close()
                retired++
            }
            pages[index] = null
            host.discard(index)
        }
        while (pages.isNotEmpty() && pages.last() == null) pages.removeAt(pages.lastIndex)
    }

    private fun width(page: Page<*>) = page.layout.columns * page.layout.cellWidth

    private fun height(page: Page<*>) = page.layout.rows * page.layout.cellHeight

    private fun cell(page: Page<*>, slot: Int): IRect {
        val layout = page.layout
        return IRect.makeXYWH(
            slot % layout.columns * layout.cellWidth,
            slot / layout.columns * layout.cellHeight,
            layout.cellWidth,
            layout.cellHeight,
        )
    }

    /** False while a deferred host is still copying [result]. */
    private fun publish(result: Pending<I>): Boolean {
        val page = checkNotNull(pages[result.page]) { "A page with a pending copy was discarded" }
        val copy = host.snapshot(result.page, result.buffer, width(page), height(page)) ?: return false
        prepared++
        val changed = HashSet<Variant>()
        result.entries.forEach { entry ->
            if (page.slots[entry.slot] !== entry) return@forEach
            entry.hasImage = true
            changed += entry.variant
        }
        // The page kept the pixels of every other variant, so all of them move to the new copy.
        val size = page.size
        val regions = HashMap<Variant, NativeImageRegion>()
        page.slots.forEach { entry ->
            if (entry == null || !entry.hasImage) return@forEach
            val cell = cell(page, entry.slot)
            regions[entry.variant] =
                NativeImageRegion(
                    copy,
                    Rect.makeXYWH(cell.left.toFloat(), cell.top.toFloat(), size.width.toFloat(), size.height.toFloat()),
                )
        }
        ComposeThread.call { host.publish(regions, changed, emptySet()) }
        if (page.owner.image != null) retired++
        page.owner.replace(copy)
        return true
    }

    /** Forgets every slot, releases page images and discards pages, for example after a resource reload. */
    fun reset() {
        pending.clear()
        byVariant.clear()
        settling.clear()
        ComposeThread.call { host.clear() }
        pages.forEachIndexed { index, page ->
            if (page == null) return@forEachIndexed
            if (page.owner.image != null) {
                page.owner.close()
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
        /** Frames a new size of an already drawn icon must stay demanded before it is drawn. */
        const val SETTLE_FRAMES = 2

        /** The most slots a page has by default; a larger frame budget draws several pages. */
        const val PAGE_CAPACITY = 64
    }
}
