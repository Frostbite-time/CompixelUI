package dev.compixel.bridge

import kotlin.math.ceil
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
 * Icons are requested at an image size, the pixels drawn for their 16 GUI units: normally the pixels an icon occupies
 * on screen, so it is sampled pixel for pixel. An icon shown at two sizes has two variants, and a page holds variants
 * of one size. Every image starts on a whole page pixel, as the game's own items start on a screen pixel. A new size of
 * an icon that already has an image waits until it has been demanded for [SETTLE_FRAMES] frames, so a size that changes
 * every frame, as in an animation, is not drawn at every step. Until the new size is drawn, the icon's other images
 * stay cached, so the host can show the nearest one.
 *
 * Every demanded variant gets a slot, and pages are added as demand grows. The atlas keeps `cacheCapacity` variants, or
 * every demanded variant when more are demanded: variants no longer demanded stay cached while they fit, so they return
 * without drawing, and otherwise the least recently demanded give up their slots. A page without variants is discarded.
 * Slots remain stable while their variants are cached.
 *
 * The host keeps every page's pixels. Each [prepare] draws only the due variants of one page into their own cells, at
 * most `preparationsPerFrame` of them, choosing the page whose due variant has waited longest. One snapshot then
 * replaces that page's image: its other variants move to the snapshot with the same pixels, and only the drawn ones are
 * reported as changed. A deferred host publishes a page on a later frame, once its copy is complete, and alternates two
 * buffers. Call every member on the render thread; publication runs on the Compose thread.
 */
class NativeIconAtlas<I : Any>(
    private val cacheCapacity: Int,
    preparationsPerFrame: Int,
    private val host: Host<I>,
) : AutoCloseable {
    /** An icon drawn with [imageSize] pixels for its 16 GUI units. */
    data class Variant(val id: Long, val imageSize: Int)

    /** [icon] demanded with [imageSize] pixels for its 16 GUI units. */
    class Request<I : Any>(val icon: I, val imageSize: Int) {
        init {
            require(imageSize in IMAGE_SIZES)
        }
    }

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
         * Draws [icons] into [page], whose [width] x [height] pixels stay the same for the page's lifetime and show 16
         * GUI units with [imageSize] pixels. Only their cells are cleared first; the rest of the page keeps its pixels.
         * A page drawn for the first time, or again after [discard], starts transparent. A deferred host also starts
         * copying the page for [snapshot] through [buffer].
         */
        fun draw(page: Int, buffer: Int, imageSize: Int, width: Int, height: Int, icons: List<Placement<I>>)

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

    /**
     * An icon, the top-left corner of its image in page GUI units, which falls on a whole pixel, and its cell's page
     * pixels with a top-left origin. A cell spans at least [SLOT_UNITS] GUI units, the image and a gutter for stack
     * counts and shadows. Neighbouring cells share their edges, so clearing one never touches another.
     */
    class Placement<I : Any>(val icon: I, val x: Float, val y: Float, val cell: IRect)

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
        val variant: Variant,
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

    private class Page<I : Any>(capacity: Int, val imageSize: Int) {
        val slots = arrayOfNulls<Entry<I>>(capacity)
        var used = 0
        var image: Image? = null
    }

    private class Pending<I : Any>(val buffer: Int, val page: Int, val entries: List<Entry<I>>)

    init {
        require(cacheCapacity >= 0 && preparationsPerFrame > 0)
    }

    /** Slots per page. A page never holds more icons than one frame may draw. */
    val pageCapacity = preparationsPerFrame
    private val columns = ceil(sqrt(pageCapacity.toDouble())).toInt()
    private val rows = (pageCapacity + columns - 1) / columns
    /** Copy buffers a deferred host must provide. */
    val buffers = if (host.immediate) 1 else 2

    private val pages = ArrayList<Page<I>?>()
    private val byVariant = HashMap<Variant, Entry<I>>()
    private var visible = LinkedHashMap<Variant, I>()
    /** New sizes of icons that already have an image, and the frame since which each has been demanded. */
    private val settling = HashMap<Variant, Long>()
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
                cachedImages = visible.keys.count { byVariant[it]?.hasImage == true },
                pendingImages = visible.keys.count { byVariant[it]?.hasImage != true },
                preparedImages = prepared,
                retiredImages = retired,
                dynamicVariants =
                    visible.keys.count { variant ->
                        byVariant[variant]?.let { it.hasImage && it.refresh.kind != NativeIconRefresh.Kind.STATIC } ==
                            true
                    },
                animationRefreshes = animationRefreshes,
                pages = pages.count { it != null },
                drawnIcons = drawnIcons,
            )

    /** Replaces the demanded variants, typically after each recorded Compose frame. */
    fun recorded(requests: List<Request<I>>) {
        visible = requests.associateByTo(LinkedHashMap(), { Variant(host.id(it.icon), it.imageSize) }, { it.icon })
    }

    /** Draws the due variants of at most one page. Returns true when published regions changed. */
    fun prepare(now: Long, tick: Long, guiScale: Double): Boolean {
        check(!closed)
        frame++
        var changed = false
        pending?.let {
            // The pending page is not redrawn until its copy completes, possibly several frames later.
            if (!publish(it)) return false
            pending = null
            changed = true
        }
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

        val index = duePage(now, tick, guiScale) ?: return changed
        val page = checkNotNull(pages[index])
        val entries = page.slots.filterNotNull().filter { it.variant in visible && due(it, now, tick, guiScale) }
        buffer = (buffer + 1) % buffers
        val size = page.imageSize
        host.draw(
            index,
            buffer,
            size,
            width(page),
            height(page),
            entries.map { entry ->
                val cell = cell(page, entry.slot)
                Placement(entry.icon, cell.left * ICON_UNITS / size, cell.top * ICON_UNITS / size, cell)
            },
        )
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
        var index = pages.indexOfFirst { it != null && it.imageSize == variant.imageSize && it.used < pageCapacity }
        if (index < 0 && byVariant.size >= maxOf(cacheCapacity, visible.size)) {
            evictable(waiting)
                .minByOrNull { it.demandedFrame }
                ?.let {
                    evict(it, removed)
                    if (checkNotNull(pages[it.page]).imageSize == variant.imageSize) index = it.page
                }
        }
        if (index < 0) {
            index = pages.indexOfFirst { it == null }
            if (index < 0) {
                index = pages.size
                pages += null
            }
            pages[index] = Page(pageCapacity, variant.imageSize)
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
            page.image?.let {
                host.release(it)
                retired++
            }
            pages[index] = null
            host.discard(index)
        }
        while (pages.isNotEmpty() && pages.last() == null) pages.removeAt(pages.lastIndex)
    }

    private fun width(page: Page<*>) = columns * pitch(page.imageSize)

    private fun height(page: Page<*>) = rows * pitch(page.imageSize)

    private fun cell(page: Page<*>, slot: Int): IRect {
        val pitch = pitch(page.imageSize)
        return IRect.makeXYWH(slot % columns * pitch, slot / columns * pitch, pitch, pitch)
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
        val size = page.imageSize.toFloat()
        val regions = HashMap<Variant, NativeImageRegion>()
        page.slots.forEach { entry ->
            if (entry == null || !entry.hasImage) return@forEach
            val cell = cell(page, entry.slot)
            regions[entry.variant] =
                NativeImageRegion(copy, Rect.makeXYWH(cell.left.toFloat(), cell.top.toFloat(), size, size))
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
        byVariant.clear()
        settling.clear()
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
        /** The image sizes an atlas draws. */
        val IMAGE_SIZES = 16..256
        /** Frames a new size of an already drawn icon must stay demanded before it is drawn. */
        const val SETTLE_FRAMES = 2

        /** The image size for an icon that occupies [pixels] on screen, within [IMAGE_SIZES]. */
        fun imageSize(pixels: Int): Int = pixels.coerceIn(IMAGE_SIZES)

        /** Whole pixels from one cell to the next, so every image starts on a pixel. */
        private fun pitch(imageSize: Int) = ceil(SLOT_UNITS * imageSize / ICON_UNITS).toInt()
    }
}
