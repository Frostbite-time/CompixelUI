package dev.compixel.bridge

import dev.compixel.render.NativeImageStatistics
import java.awt.EventQueue
import kotlin.test.*
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test

class NativeImageAtlasTest {
    private class Icon(val id: Long, val refresh: NativeImageRefresh = NativeImageRefresh.STATIC)

    private class Draw(
        val page: Int,
        val buffer: Int,
        val size: NativeImageAtlas.Size,
        val width: Int,
        val height: Int,
        val icons: List<NativeImageAtlas.Placement<Icon>>,
    )

    private class Host(override val immediate: Boolean = true, private val surfaces: Boolean = false) :
        NativeImageAtlas.Host<Icon> {
        val draws = mutableListOf<Draw>()
        val snapshots = mutableListOf<Image>()
        /** Snapshot indices; closed Skia images compare equal, so identity is tracked here. */
        val released = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val published = HashMap<NativeImageAtlas.Variant, NativeImageRegion>()
        val changes = mutableListOf<Set<NativeImageAtlas.Variant>>()
        val appearances = HashMap<Long, Any>()
        val appearanceCalls = HashMap<Long, Int>()
        var copyReady = true

        override fun layout(size: NativeImageAtlas.Size, capacity: Int) =
            if (surfaces) NativeImageAtlas.Layout.single(size)
            else
                NativeImageAtlas.Layout.grid(
                    size,
                    capacity,
                    kotlin.math.ceil(size.width / 8.0).toInt(),
                    kotlin.math.ceil(size.height / 8.0).toInt(),
                )

        override fun id(icon: Icon) = icon.id

        override fun refresh(icon: Icon) = icon.refresh

        override fun appearance(icon: Icon): Any? {
            appearanceCalls.merge(icon.id, 1, Int::plus)
            return appearances[icon.id]
        }

        override fun draw(
            page: Int,
            buffer: Int,
            size: NativeImageAtlas.Size,
            width: Int,
            height: Int,
            icons: List<NativeImageAtlas.Placement<Icon>>,
        ) {
            draws += Draw(page, buffer, size, width, height, icons)
        }

        override fun snapshot(page: Int, buffer: Int, width: Int, height: Int): Image? {
            if (!copyReady) return null
            val surface = Surface.makeRasterN32Premul(4, 4)
            return try {
                surface.makeImageSnapshot().also { snapshots += it }
            } finally {
                surface.close()
            }
        }

        override fun release(image: Image) {
            val index = snapshots.indexOfFirst { it === image }
            check(index >= 0 && index !in released) { "Unknown or repeated release" }
            released += index
        }

        override fun discard(page: Int) {
            discarded += page
        }

        override fun publish(
            regions: Map<NativeImageAtlas.Variant, NativeImageRegion>,
            changed: Set<NativeImageAtlas.Variant>,
            removed: Set<NativeImageAtlas.Variant>,
        ) {
            check(EventQueue.isDispatchThread())
            check(changed.all { it in regions }) { "A changed variant needs its new region" }
            removed.forEach(published::remove)
            published.putAll(regions)
            if (changed.isNotEmpty()) changes += changed
        }

        override fun clear() {
            check(EventQueue.isDispatchThread())
            published.clear()
        }

        fun drawnIds(draw: Int) = draws[draw].icons.map { it.icon.id }

        /** Published icon ids of one image size. */
        fun ids(size: Int = 16) =
            published.keys.filter { it.size == NativeImageAtlas.Size(size, size) }.mapTo(HashSet()) { it.id }

        fun image(id: Long, size: Int = 16) = snapshots.indexOfFirst {
            it === published.getValue(NativeImageAtlas.Variant(id, NativeImageAtlas.Size(size, size))).image
        }

        fun region(id: Long, size: Int = 16) =
            published.getValue(NativeImageAtlas.Variant(id, NativeImageAtlas.Size(size, size))).source.let {
                listOf(it.left, it.top, it.right, it.bottom)
            }

        fun changedIds(change: Int) = changes[change].mapTo(HashSet()) { it.id }
    }

    private fun icons(count: Int, refresh: NativeImageRefresh = NativeImageRefresh.STATIC) =
        List(count) { Icon(it.toLong(), refresh) }

    private fun at(icons: List<Icon>, size: Int = 16) = icons.map {
        NativeImageAtlas.Request(it, NativeImageAtlas.Size(size, size))
    }

    private fun NativeImageAtlas<Icon>.prepare(now: Long = 0, tick: Long = 0, guiScale: Double = 1.0) =
        prepare(now, tick, guiScale)

    @Test
    fun layoutFitsTheBudgetAndCellsShareTheirEdges() {
        val host = Host()
        val atlas = NativeImageAtlas(128, 64, host)
        assertEquals(64, atlas.pageCapacity)
        assertEquals(1, atlas.buffers)
        atlas.recorded(at(icons(2), 64))
        assertTrue(atlas.prepare())
        assertEquals(64, host.draws[0].size.width)
        assertEquals(576 to 576, host.draws[0].width to host.draws[0].height)
        assertEquals(IRect.makeLTRB(72, 0, 144, 72), host.draws[0].icons[1].cell)
        assertEquals(72f to 0f, host.draws[0].icons[1].let { it.x to it.y })
        // The page size follows the frame budget, not the cache.
        val deferred = Host(immediate = false)
        val small = NativeImageAtlas(1, 10, deferred)
        assertEquals(10, small.pageCapacity)
        assertEquals(2, small.buffers)
        small.recorded(at(icons(10), 20))
        assertFalse(small.prepare())
        // A cell's 18 GUI units take 22.5 pixels at this size. Cells take 23, so each image starts on a pixel.
        assertEquals(92 to 69, deferred.draws[0].width to deferred.draws[0].height)
        val cells = deferred.draws[0].icons.map { it.cell }
        assertEquals(cells[0].right, cells[1].left)
        assertEquals(cells[0].bottom, cells[4].top)
        assertEquals(IRect.makeLTRB(69, 0, 92, 23), cells[3])
        assertEquals(69, cells[8].bottom)
        assertEquals(23f to 23f, deferred.draws[0].icons[5].let { it.x to it.y })
        assertTrue(small.prepare())
        assertEquals(listOf(23f, 23f, 43f, 43f), deferred.region(5, 20))
        atlas.close()
        small.close()
    }

    @Test
    fun everyVisibleIconGetsItsRegionAndStaticPagesAreDrawnOnce() {
        val host = Host()
        val atlas = NativeImageAtlas(8, 4, host)
        atlas.recorded(at(icons(6), 64))
        assertTrue(atlas.prepare(guiScale = 2.0))
        assertEquals(listOf(0L, 1, 2, 3), host.drawnIds(0))
        assertEquals(listOf(0f to 0f, 72f to 0f, 0f to 72f, 72f to 72f), host.draws[0].icons.map { it.x to it.y })
        assertTrue(atlas.prepare(guiScale = 2.0))
        assertEquals(1, host.draws[1].page)
        assertEquals(listOf(4L, 5), host.drawnIds(1))
        assertFalse(atlas.prepare(guiScale = 2.0))
        assertEquals(2, host.draws.size)
        assertEquals(listOf(setOf(0L, 1, 2, 3), setOf(4L, 5)), host.changes.indices.map(host::changedIds))

        assertEquals(listOf(72f, 72f, 136f, 136f), host.region(3, 64))
        assertEquals(0, host.image(3, 64))
        assertEquals(listOf(72f, 0f, 136f, 64f), host.region(5, 64))
        assertEquals(1, host.image(5, 64))
        assertEquals(NativeImageStatistics(6, 6, 0, 2, 0, 0, 0, 2, 6), atlas.statistics)
        atlas.close()
    }

    @Test
    fun onlyDueIconsAreDrawnAndTheRestMoveToTheNewImage() {
        val host = Host()
        val atlas = NativeImageAtlas(8, 4, host)
        atlas.recorded(at(listOf(Icon(0, NativeImageRefresh.FRAME)) + icons(4).drop(1)))
        assertTrue(atlas.prepare())
        repeat(2) { assertTrue(atlas.prepare()) }
        assertEquals(listOf(listOf(0L, 1, 2, 3), listOf(0L), listOf(0L)), host.draws.indices.map(host::drawnIds))
        assertEquals(listOf(setOf(0L, 1, 2, 3), setOf(0L), setOf(0L)), host.changes.indices.map(host::changedIds))
        // Static icons keep their pixels on the page, so they follow each new image without changing.
        assertEquals((0L until 4).associateWith { 2 }, (0L until 4).associateWith { host.image(it) })
        assertEquals(listOf(0, 1), host.released)
        assertEquals(NativeImageStatistics(4, 4, 0, 3, 2, 1, 2, 1, 6), atlas.statistics)
        atlas.close()
    }

    @Test
    fun animatedPagesRotateSoNoIconStarves() {
        val host = Host()
        val atlas = NativeImageAtlas(16, 4, host)
        atlas.recorded(at(icons(16, NativeImageRefresh.FRAME)))
        repeat(12) { atlas.prepare() }
        val drawn = host.draws.flatMap { draw -> draw.icons.map { it.icon.id } }.groupingBy { it }.eachCount()
        assertEquals((0L until 16).associateWith { 3 }, drawn)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3), host.draws.map { it.page })
        assertEquals(32, atlas.statistics.animationRefreshes)
        assertEquals(16, atlas.statistics.dynamicVariants)
        atlas.close()
    }

    @Test
    fun demandBeyondTheCacheAddsPages() {
        val host = Host()
        val atlas = NativeImageAtlas(2, 2, host)
        atlas.recorded(at(icons(5)))
        repeat(3) { assertTrue(atlas.prepare()) }
        assertFalse(atlas.prepare())
        assertEquals(listOf(0, 1, 2), host.draws.map { it.page })
        assertEquals((0L until 5).toSet(), host.ids())
        assertEquals(NativeImageStatistics(5, 5, 0, 3, 0, 0, 0, 3, 5), atlas.statistics)
        atlas.close()
    }

    @Test
    fun hiddenIconsStayCachedUntilTheCacheIsFull() {
        val host = Host()
        val atlas = NativeImageAtlas(2, 2, host)
        val (a, b, c) = icons(3)
        atlas.recorded(at(listOf(a, b)))
        atlas.prepare()
        // One hidden icon fits the cache beside the visible one, so it keeps its slot and region.
        atlas.recorded(at(listOf(b)))
        assertFalse(atlas.prepare())
        assertEquals(setOf(0L, 1), host.ids())
        atlas.recorded(at(listOf(a, b)))
        assertFalse(atlas.prepare())
        assertEquals(1, host.draws.size)

        // A new icon takes the least recently demanded hidden icon's slot once the cache is full.
        atlas.recorded(at(listOf(b, c)))
        assertTrue(atlas.prepare())
        assertEquals(listOf(2L), host.drawnIds(1))
        assertEquals(listOf(0f to 0f), host.draws[1].icons.map { it.x to it.y })
        assertEquals(setOf(1L, 2), host.ids())
        assertEquals(1, atlas.statistics.pages)
        atlas.close()
    }

    @Test
    fun trimmedPagesAreDiscardedAndReused() {
        val host = Host()
        val atlas = NativeImageAtlas(2, 2, host)
        atlas.recorded(at(icons(6)))
        repeat(3) { atlas.prepare() }
        // The atlas keeps as many icons as are demanded, or the cache's worth if fewer are.
        atlas.recorded(at(icons(6).drop(2)))
        assertTrue(atlas.prepare())
        atlas.recorded(at(icons(6).drop(4)))
        assertTrue(atlas.prepare())
        assertEquals(listOf(0, 1), host.discarded)
        assertEquals(listOf(0, 1), host.released.sorted())
        atlas.recorded(at(icons(6).drop(5)))
        assertFalse(atlas.prepare())
        assertEquals(setOf(4L, 5), host.ids())
        assertEquals(1, atlas.statistics.pages)

        // New demand reuses the first free page index; the hidden icon goes once the cache is exceeded.
        atlas.recorded(at(listOf(Icon(5), Icon(6), Icon(7))))
        assertTrue(atlas.prepare())
        assertEquals(0 to listOf(6L, 7L), host.draws.last().page to host.drawnIds(host.draws.lastIndex))
        assertTrue(atlas.prepare())
        assertEquals(setOf(5L, 6, 7), host.ids())
        assertEquals(2, atlas.statistics.pages)
        atlas.close()
    }

    @Test
    fun deferredHostsPublishOnTheNextFrameAndAlternateBuffersPerPage() {
        val host = Host(immediate = false)
        val atlas = NativeImageAtlas(4, 2, host)
        atlas.recorded(at(icons(4, NativeImageRefresh.FRAME)))
        assertFalse(atlas.prepare())
        assertTrue(host.published.isEmpty())
        assertTrue(atlas.prepare())
        assertEquals(setOf(0L, 1), host.ids())
        atlas.prepare()
        assertEquals(setOf(0L, 1, 2, 3), host.ids())
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1), host.draws.map { it.page to it.buffer })
        atlas.close()
    }

    @Test
    fun deferredCopiesHoldTheirPageUntilComplete() {
        val host = Host(immediate = false)
        val atlas = NativeImageAtlas(4, 2, host)
        atlas.recorded(at(icons(4)))
        assertFalse(atlas.prepare())
        host.copyReady = false
        repeat(3) { assertFalse(atlas.prepare()) }
        assertEquals(1, host.draws.size)
        assertTrue(host.published.isEmpty())
        host.copyReady = true
        assertTrue(atlas.prepare())
        assertEquals(setOf(0L, 1), host.ids())
        assertEquals(listOf(0 to 0, 1 to 0), host.draws.map { it.page to it.buffer })
        assertTrue(atlas.prepare())
        assertEquals(setOf(0L, 1, 2, 3), host.ids())
        atlas.close()
    }

    @Test
    fun changedAppearancesRedrawOnlyTheirIconAndAreComparedOncePerTick() {
        val host = Host()
        val atlas = NativeImageAtlas(8, 4, host)
        val changing = icons(8, NativeImageRefresh.ON_CHANGE)
        changing.forEach { host.appearances[it.id] = "still" }
        atlas.recorded(at(changing))
        repeat(2) { assertTrue(atlas.prepare()) }
        assertFalse(atlas.prepare(tick = 1))
        host.appearances[5] = "moved"
        // The tick that already compared its appearances notices the change only on the next tick.
        assertFalse(atlas.prepare(tick = 1))
        assertTrue(atlas.prepare(tick = 2))
        assertEquals(listOf(5L), host.drawnIds(2))
        assertFalse(atlas.prepare(tick = 3))
        assertEquals(3, host.draws.size)
        assertEquals((0L until 8).associateWith { 4 }, host.appearanceCalls)
        atlas.close()
    }

    @Test
    fun dueRulesFollowTicksIntervalsAndGuiScale() {
        val host = Host()
        val atlas = NativeImageAtlas(3, 1, host)
        val ticking = Icon(0, NativeImageRefresh.GAME_TICK)
        val timed = Icon(1, NativeImageRefresh.every(100))
        val still = Icon(2)
        atlas.recorded(at(listOf(ticking, timed, still)))
        repeat(3) { atlas.prepare(0, 7, 2.0) }
        assertFalse(atlas.prepare(99_000_000, 7, 2.0))
        assertTrue(atlas.prepare(99_000_000, 8, 2.0))
        assertEquals(0L, host.drawnIds(3).single())
        assertTrue(atlas.prepare(100_000_000, 8, 2.0))
        assertEquals(1L, host.drawnIds(4).single())
        assertFalse(atlas.prepare(100_000_000, 8, 2.0))
        // A GUI scale change makes every icon due; the page drawn longest ago goes first.
        repeat(3) { assertTrue(atlas.prepare(100_000_000, 8, 3.0)) }
        assertEquals(listOf(2L, 0, 1), (5..7).map { host.drawnIds(it).single() })
        assertFalse(atlas.prepare(100_000_000, 8, 3.0))
        atlas.close()
    }

    @Test
    fun eachImageSizeHasItsOwnPagesAndRegions() {
        val host = Host()
        val atlas = NativeImageAtlas(8, 4, host)
        val (a, b) = icons(2)
        // An icon shown at two sizes has two variants, each drawn at its own size.
        atlas.recorded(at(listOf(a, b), 32) + at(listOf(a), 48))
        repeat(2) { assertTrue(atlas.prepare()) }
        assertFalse(atlas.prepare())
        assertEquals(listOf(0 to 72, 1 to 108), host.draws.map { it.page to it.width })
        assertEquals(listOf(listOf(0L, 1), listOf(0L)), host.draws.indices.map(host::drawnIds))
        assertEquals(listOf(0f, 0f, 32f, 32f), host.region(0, 32))
        assertEquals(listOf(36f, 0f, 68f, 32f), host.region(1, 32))
        assertEquals(listOf(0f, 0f, 48f, 48f), host.region(0, 48))
        assertEquals(NativeImageStatistics(3, 3, 0, 2, 0, 0, 0, 2, 3), atlas.statistics)
        atlas.close()
    }

    @Test
    fun aNewSizeSettlesAndTheOldImageStaysUntilItIsDrawn() {
        val host = Host()
        val atlas = NativeImageAtlas(1, 4, host)
        val icon = Icon(0)
        atlas.recorded(at(listOf(icon), 32))
        assertTrue(atlas.prepare())
        atlas.recorded(at(listOf(icon), 48))
        repeat(NativeImageAtlas.SETTLE_FRAMES) { assertFalse(atlas.prepare()) }
        assertEquals(1, host.draws.size)
        // Until the new size is drawn, the old image stays published, although the cache holds one variant.
        assertEquals(setOf(0L), host.ids(32))
        assertTrue(atlas.prepare())
        assertEquals(1 to 108, host.draws[1].page to host.draws[1].width)
        assertEquals(setOf(0L), host.ids(32))
        assertEquals(setOf(0L), host.ids(48))
        // Once it is drawn, the old size goes like any other hidden variant beyond the cache.
        assertTrue(atlas.prepare())
        assertEquals(emptySet(), host.ids(32))
        assertEquals(listOf(0), host.discarded)
        assertEquals(1, atlas.statistics.pages)
        atlas.close()
    }

    @Test
    fun aSizeThatChangesEveryFrameIsDrawnOnceItSettles() {
        val host = Host()
        val atlas = NativeImageAtlas(8, 4, host)
        val icon = Icon(0)
        atlas.recorded(at(listOf(icon), 32))
        assertTrue(atlas.prepare())
        for (size in 33..40) {
            atlas.recorded(at(listOf(icon), size))
            assertFalse(atlas.prepare())
        }
        assertEquals(1, host.draws.size)
        // The last size has been demanded for one frame already.
        repeat(NativeImageAtlas.SETTLE_FRAMES - 1) { assertFalse(atlas.prepare()) }
        assertTrue(atlas.prepare())
        assertEquals(listOf(0L), host.drawnIds(1))
        assertEquals(setOf(0L), host.ids(40))
        atlas.close()
    }

    @Test
    fun resetAndCloseReleaseEveryPageImage() {
        val host = Host()
        val atlas = NativeImageAtlas(8, 4, host)
        atlas.recorded(at(icons(8, NativeImageRefresh.FRAME)))
        repeat(5) { atlas.prepare() }
        assertEquals(5, host.snapshots.size)
        assertEquals(listOf(0, 1, 2), host.released)
        atlas.reset()
        assertTrue(host.published.isEmpty())
        assertEquals(listOf(0, 1, 2, 3, 4), host.released.sorted())
        assertEquals(listOf(0, 1), host.discarded)
        atlas.prepare()
        assertEquals(setOf(0L, 1, 2, 3), host.ids())
        atlas.close()
        atlas.close()
        assertEquals(host.snapshots.indices.toList(), host.released.sorted())
        assertEquals(listOf(0, 1, 0, 1), host.discarded)
        with(atlas.statistics) { assertEquals(preparedImages, retiredImages) }
        assertFailsWith<IllegalStateException> { atlas.prepare() }
    }

    @Test
    fun rectangularSurfacesShareOneBudgetAndKeepTheirActualDimensions() {
        val host = Host(surfaces = true)
        val atlas = NativeImageAtlas(0, 3, host, pageCapacity = 1)
        val dimensions = listOf(320 to 80, 80 to 320, 512 to 96, 100 to 40)
        atlas.recorded(
            dimensions.mapIndexed { index, (width, height) ->
                NativeImageAtlas.Request(
                    Icon(index.toLong(), NativeImageRefresh.FRAME),
                    NativeImageAtlas.Size(width, height),
                )
            }
        )
        assertTrue(atlas.prepare())
        assertEquals(3, host.draws.size)
        assertEquals(3, host.draws.map { it.icons.single().icon.id }.toSet().size)
        host.draws.forEach { drawing ->
            assertEquals(drawing.size.width, drawing.width)
            assertEquals(drawing.size.height, drawing.height)
            assertEquals(0f to 0f, drawing.icons.single().let { it.x to it.y })
        }
        assertTrue(atlas.prepare())
        assertEquals(6, host.draws.size)
        assertTrue(host.draws.drop(3).any { it.icons.single().icon.id == 3L })
        atlas.recorded(emptyList())
        atlas.prepare()
        assertTrue(host.published.isEmpty())
        assertEquals(0, atlas.statistics.pages)
        atlas.close()
        assertEquals(atlas.statistics.preparedImages, atlas.statistics.retiredImages)
    }

    @Test
    fun independentDeferredTargetsWaitForTheirCopiesAndRetireOnReset() {
        val host = Host(immediate = false, surfaces = true)
        val atlas = NativeImageAtlas(0, 2, host, pageCapacity = 1)
        atlas.recorded(
            listOf(
                NativeImageAtlas.Request(Icon(1), NativeImageAtlas.Size(300, 90)),
                NativeImageAtlas.Request(Icon(2), NativeImageAtlas.Size(90, 300)),
            )
        )
        assertFalse(atlas.prepare())
        assertEquals(2, host.draws.size)
        host.copyReady = false
        assertFalse(atlas.prepare())
        assertEquals(2, host.draws.size)
        host.copyReady = true
        assertTrue(atlas.prepare())
        assertEquals(
            setOf(NativeImageAtlas.Size(300, 90), NativeImageAtlas.Size(90, 300)),
            host.published.keys.map { it.size }.toSet(),
        )
        host.published.forEach { (variant, region) ->
            assertEquals(variant.size.width.toFloat(), region.source.width)
            assertEquals(variant.size.height.toFloat(), region.source.height)
        }
        atlas.reset()
        assertTrue(host.published.isEmpty())
        assertEquals(2, host.released.size)
        atlas.close()
    }

    @Test
    fun aBudgetBeyondOnePageDrawsSeveralPagesInOneFrame() {
        val host = Host()
        val atlas = NativeImageAtlas(0, 130, host)
        assertEquals(NativeImageAtlas.PAGE_CAPACITY, atlas.pageCapacity)
        atlas.recorded(at(icons(200)))
        assertTrue(atlas.prepare())
        assertEquals(listOf(64, 64, 2), host.draws.map { it.icons.size })
        assertEquals(listOf(0, 1, 2), host.draws.map { it.page })
        assertEquals(130, host.ids().size)
        assertTrue(atlas.prepare())
        assertEquals(200, host.ids().size)
        assertEquals(listOf(62, 8), host.draws.drop(3).map { it.icons.size })
        atlas.close()
    }

    @Test
    fun deferredPagesOfOneFrameWaitTogetherAndKeepTheirOwnBuffers() {
        val host = Host(immediate = false)
        val atlas = NativeImageAtlas(0, 128, host)
        atlas.recorded(at(icons(128, NativeImageRefresh.FRAME)))
        assertFalse(atlas.prepare())
        host.copyReady = false
        assertFalse(atlas.prepare())
        assertEquals(2, host.draws.size)
        host.copyReady = true
        assertTrue(atlas.prepare())
        assertEquals(128, host.ids().size)
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), host.draws.map { it.page to it.buffer })
        atlas.close()
    }
}
