package dev.compixel.bridge

import java.awt.EventQueue
import kotlin.test.*
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test

class NativeIconAtlasTest {
    private class Icon(val id: Long, val refresh: NativeIconRefresh = NativeIconRefresh.STATIC)

    private class Draw(val page: Int, val buffer: Int, val icons: List<NativeIconAtlas.Placement<Icon>>)

    private class Host(override val immediate: Boolean = true) : NativeIconAtlas.Host<Icon> {
        val draws = mutableListOf<Draw>()
        val snapshots = mutableListOf<Image>()
        /** Snapshot indices; closed Skia images compare equal, so identity is tracked here. */
        val released = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val published = HashMap<Long, NativeImageRegion>()
        val changes = mutableListOf<Set<Long>>()
        val appearances = HashMap<Long, Any>()
        val appearanceCalls = HashMap<Long, Int>()
        var copyReady = true

        override fun id(icon: Icon) = icon.id

        override fun refresh(icon: Icon) = icon.refresh

        override fun appearance(icon: Icon): Any? {
            appearanceCalls.merge(icon.id, 1, Int::plus)
            return appearances[icon.id]
        }

        override fun draw(page: Int, buffer: Int, icons: List<NativeIconAtlas.Placement<Icon>>) {
            draws += Draw(page, buffer, icons)
        }

        override fun snapshot(page: Int, buffer: Int): Image? {
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

        override fun publish(regions: Map<Long, NativeImageRegion>, changed: Set<Long>, removed: Set<Long>) {
            check(EventQueue.isDispatchThread())
            check(changed.all { it in regions }) { "A changed icon needs its new region" }
            removed.forEach(published::remove)
            published.putAll(regions)
            if (changed.isNotEmpty()) changes += changed
        }

        override fun clear() {
            check(EventQueue.isDispatchThread())
            published.clear()
        }

        fun drawnIds(draw: Int) = draws[draw].icons.map { it.icon.id }

        fun image(id: Long) = snapshots.indexOfFirst { it === published.getValue(id).image }

        fun region(id: Long) = published.getValue(id).source.let { listOf(it.left, it.top, it.right, it.bottom) }
    }

    private fun icons(count: Int, refresh: NativeIconRefresh = NativeIconRefresh.STATIC) =
        List(count) { Icon(it.toLong(), refresh) }

    @Test
    fun layoutFitsTheBudgetAndCellsShareTheirEdges() {
        val atlas = NativeIconAtlas(128, 64, Host())
        assertEquals(64, atlas.pageCapacity)
        assertEquals(144 to 144, atlas.guiWidth to atlas.guiHeight)
        assertFalse(atlas.prepare(0, 0, 1.0, 64))
        assertEquals(576 to 576, atlas.width to atlas.height)
        assertEquals(1, atlas.buffers)
        assertEquals(IRect.makeLTRB(72, 0, 144, 72), atlas.cell(NativeIconAtlas.Placement(Icon(0), 18, 0)))
        // The page size follows the frame budget, not the cache.
        val small = NativeIconAtlas(1, 10, Host(immediate = false))
        assertFalse(small.prepare(0, 0, 1.0, 20))
        assertEquals(10, small.pageCapacity)
        assertEquals(72 to 54, small.guiWidth to small.guiHeight)
        assertEquals(90 to 68, small.width to small.height)
        assertEquals(2, small.buffers)
        val first = small.cell(NativeIconAtlas.Placement(Icon(0), 0, 0))
        val right = small.cell(NativeIconAtlas.Placement(Icon(1), 18, 0))
        val below = small.cell(NativeIconAtlas.Placement(Icon(2), 0, 18))
        assertEquals(first.right, right.left)
        assertEquals(first.bottom, below.top)
        assertEquals(IRect.makeLTRB(68, 45, 90, 68), small.cell(NativeIconAtlas.Placement(Icon(3), 54, 36)))
    }

    @Test
    fun everyVisibleIconGetsItsRegionAndStaticPagesAreDrawnOnce() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, host)
        atlas.recorded(icons(6))
        assertTrue(atlas.prepare(0, 0, 2.0, 64))
        assertEquals(listOf(0L, 1, 2, 3), host.drawnIds(0))
        assertEquals(listOf(0 to 0, 18 to 0, 0 to 18, 18 to 18), host.draws[0].icons.map { it.x to it.y })
        assertTrue(atlas.prepare(0, 0, 2.0, 64))
        assertEquals(1, host.draws[1].page)
        assertEquals(listOf(4L, 5), host.drawnIds(1))
        assertFalse(atlas.prepare(0, 0, 2.0, 64))
        assertEquals(2, host.draws.size)
        assertEquals(listOf(setOf(0L, 1, 2, 3), setOf(4L, 5)), host.changes)

        assertEquals(listOf(72f, 72f, 136f, 136f), host.region(3))
        assertEquals(0, host.image(3))
        assertEquals(listOf(72f, 0f, 136f, 64f), host.region(5))
        assertEquals(1, host.image(5))
        assertEquals(NativeIconAtlas.Statistics(6, 6, 0, 2, 0, 0, 0, 2, 6), atlas.statistics)
        atlas.close()
    }

    @Test
    fun onlyDueIconsAreDrawnAndTheRestMoveToTheNewImage() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, host)
        atlas.recorded(listOf(Icon(0, NativeIconRefresh.FRAME)) + icons(4).drop(1))
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        repeat(2) { assertTrue(atlas.prepare(0, 0, 1.0, 16)) }
        assertEquals(listOf(listOf(0L, 1, 2, 3), listOf(0L), listOf(0L)), host.draws.indices.map(host::drawnIds))
        assertEquals(listOf(setOf(0L, 1, 2, 3), setOf(0L), setOf(0L)), host.changes)
        // Static icons keep their pixels on the page, so they follow each new image without changing.
        assertEquals((0L until 4).associateWith { 2 }, (0L until 4).associateWith(host::image))
        assertEquals(listOf(0, 1), host.released)
        assertEquals(NativeIconAtlas.Statistics(4, 4, 0, 3, 2, 1, 2, 1, 6), atlas.statistics)
        atlas.close()
    }

    @Test
    fun animatedPagesRotateSoNoIconStarves() {
        val host = Host()
        val atlas = NativeIconAtlas(16, 4, host)
        atlas.recorded(icons(16, NativeIconRefresh.FRAME))
        repeat(12) { atlas.prepare(0, 0, 1.0, 16) }
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
        val atlas = NativeIconAtlas(2, 2, host)
        atlas.recorded(icons(5))
        repeat(3) { assertTrue(atlas.prepare(0, 0, 1.0, 16)) }
        assertFalse(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(listOf(0, 1, 2), host.draws.map { it.page })
        assertEquals((0L until 5).toSet(), host.published.keys)
        assertEquals(NativeIconAtlas.Statistics(5, 5, 0, 3, 0, 0, 0, 3, 5), atlas.statistics)
        atlas.close()
    }

    @Test
    fun hiddenIconsStayCachedUntilTheCacheIsFull() {
        val host = Host()
        val atlas = NativeIconAtlas(2, 2, host)
        val (a, b, c) = icons(3)
        atlas.recorded(listOf(a, b))
        atlas.prepare(0, 0, 1.0, 16)
        // One hidden icon fits the cache beside the visible one, so it keeps its slot and region.
        atlas.recorded(listOf(b))
        assertFalse(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(setOf(0L, 1), host.published.keys)
        atlas.recorded(listOf(a, b))
        assertFalse(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(1, host.draws.size)

        // A new icon takes the least recently demanded hidden icon's slot once the cache is full.
        atlas.recorded(listOf(b, c))
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(listOf(2L), host.drawnIds(1))
        assertEquals(listOf(0 to 0), host.draws[1].icons.map { it.x to it.y })
        assertEquals(setOf(1L, 2), host.published.keys)
        assertEquals(1, atlas.statistics.pages)
        atlas.close()
    }

    @Test
    fun trimmedPagesAreDiscardedAndReused() {
        val host = Host()
        val atlas = NativeIconAtlas(2, 2, host)
        atlas.recorded(icons(6))
        repeat(3) { atlas.prepare(0, 0, 1.0, 16) }
        // The atlas keeps as many icons as are demanded, or the cache's worth if fewer are.
        atlas.recorded(icons(6).drop(2))
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        atlas.recorded(icons(6).drop(4))
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(listOf(0, 1), host.discarded)
        assertEquals(listOf(0, 1), host.released.sorted())
        atlas.recorded(icons(6).drop(5))
        assertFalse(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(setOf(4L, 5), host.published.keys)
        assertEquals(1, atlas.statistics.pages)

        // New demand reuses the first free page index; the hidden icon goes once the cache is exceeded.
        atlas.recorded(listOf(Icon(5), Icon(6), Icon(7)))
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(0 to listOf(6L, 7L), host.draws.last().page to host.drawnIds(host.draws.lastIndex))
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(setOf(5L, 6, 7), host.published.keys)
        assertEquals(2, atlas.statistics.pages)
        atlas.close()
    }

    @Test
    fun deferredHostsPublishOnTheNextFrameAndAlternateBuffers() {
        val host = Host(immediate = false)
        val atlas = NativeIconAtlas(4, 2, host)
        atlas.recorded(icons(4, NativeIconRefresh.FRAME))
        assertFalse(atlas.prepare(0, 0, 1.0, 16))
        assertTrue(host.published.isEmpty())
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(setOf(0L, 1), host.published.keys)
        atlas.prepare(0, 0, 1.0, 16)
        assertEquals(setOf(0L, 1, 2, 3), host.published.keys)
        assertEquals(listOf(0 to 0, 1 to 1, 0 to 0), host.draws.map { it.page to it.buffer })
        atlas.close()
    }

    @Test
    fun deferredCopiesHoldTheirPageUntilComplete() {
        val host = Host(immediate = false)
        val atlas = NativeIconAtlas(4, 2, host)
        atlas.recorded(icons(4))
        assertFalse(atlas.prepare(0, 0, 1.0, 16))
        host.copyReady = false
        repeat(3) { assertFalse(atlas.prepare(0, 0, 1.0, 16)) }
        assertEquals(1, host.draws.size)
        assertTrue(host.published.isEmpty())
        host.copyReady = true
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(setOf(0L, 1), host.published.keys)
        assertEquals(listOf(0, 1), host.draws.map { it.buffer })
        assertTrue(atlas.prepare(0, 0, 1.0, 16))
        assertEquals(setOf(0L, 1, 2, 3), host.published.keys)
        atlas.close()
    }

    @Test
    fun changedAppearancesRedrawOnlyTheirIconAndAreComparedOncePerTick() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, host)
        val changing = icons(8, NativeIconRefresh.ON_CHANGE)
        changing.forEach { host.appearances[it.id] = "still" }
        atlas.recorded(changing)
        repeat(2) { assertTrue(atlas.prepare(0, 0, 1.0, 16)) }
        assertFalse(atlas.prepare(0, 1, 1.0, 16))
        host.appearances[5] = "moved"
        // The tick that already compared its appearances notices the change only on the next tick.
        assertFalse(atlas.prepare(0, 1, 1.0, 16))
        assertTrue(atlas.prepare(0, 2, 1.0, 16))
        assertEquals(listOf(5L), host.drawnIds(2))
        assertFalse(atlas.prepare(0, 3, 1.0, 16))
        assertEquals(3, host.draws.size)
        assertEquals((0L until 8).associateWith { 4 }, host.appearanceCalls)
        atlas.close()
    }

    @Test
    fun dueRulesFollowTicksIntervalsAndGuiScale() {
        val host = Host()
        val atlas = NativeIconAtlas(3, 1, host)
        val ticking = Icon(0, NativeIconRefresh.GAME_TICK)
        val timed = Icon(1, NativeIconRefresh.every(100))
        val still = Icon(2)
        atlas.recorded(listOf(ticking, timed, still))
        repeat(3) { atlas.prepare(0, 7, 2.0, 16) }
        assertFalse(atlas.prepare(99_000_000, 7, 2.0, 16))
        assertTrue(atlas.prepare(99_000_000, 8, 2.0, 16))
        assertEquals(0L, host.drawnIds(3).single())
        assertTrue(atlas.prepare(100_000_000, 8, 2.0, 16))
        assertEquals(1L, host.drawnIds(4).single())
        assertFalse(atlas.prepare(100_000_000, 8, 2.0, 16))
        // A GUI scale change makes every icon due; the page drawn longest ago goes first.
        repeat(3) { assertTrue(atlas.prepare(100_000_000, 8, 3.0, 16)) }
        assertEquals(listOf(2L, 0, 1), (5..7).map { host.drawnIds(it).single() })
        assertFalse(atlas.prepare(100_000_000, 8, 3.0, 16))
        atlas.close()
    }

    @Test
    fun pagesAreRedrawnAtANewImageSizeOnePageAtATime() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, host)
        atlas.recorded(icons(6))
        repeat(2) { assertTrue(atlas.prepare(0, 0, 2.0, 32)) }
        assertEquals(72 to 72, atlas.width to atlas.height)
        assertEquals(listOf(36f, 36f, 68f, 68f), host.region(3))
        // Icon 5 stays cached while hidden.
        atlas.recorded(icons(5))
        assertTrue(atlas.prepare(0, 0, 3.0, 48))
        assertEquals(108 to 108, atlas.width to atlas.height)
        assertEquals(0 to listOf(0L, 1, 2, 3), host.draws[2].page to host.drawnIds(2))
        assertEquals(listOf(54f, 54f, 102f, 102f), host.region(3))
        // A page not redrawn yet keeps its image at the previous size.
        assertEquals(listOf(0f, 0f, 32f, 32f), host.region(4))
        assertEquals((0L until 6).toSet(), host.published.keys)
        assertTrue(atlas.prepare(0, 0, 3.0, 48))
        assertEquals(1 to listOf(4L), host.draws[3].page to host.drawnIds(3))
        assertEquals(listOf(0f, 0f, 48f, 48f), host.region(4))
        // The hidden icon lost its pixels with the page's old image; it is drawn once demanded again.
        assertEquals((0L until 5).toSet(), host.published.keys)
        assertFalse(atlas.prepare(0, 0, 3.0, 48))
        atlas.recorded(icons(6))
        assertTrue(atlas.prepare(0, 0, 3.0, 48))
        assertEquals(1 to listOf(5L), host.draws[4].page to host.drawnIds(4))
        assertEquals(listOf(54f, 0f, 102f, 48f), host.region(5))
        assertEquals(listOf(16, 32, 48, 256), listOf(0.5f, 2f, 3f, 20f).map { NativeIconAtlas.imageSize(it) })
        atlas.close()
    }

    @Test
    fun resetAndCloseReleaseEveryPageImage() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, host)
        atlas.recorded(icons(8, NativeIconRefresh.FRAME))
        repeat(5) { atlas.prepare(0, 0, 1.0, 16) }
        assertEquals(5, host.snapshots.size)
        assertEquals(listOf(0, 1, 2), host.released)
        atlas.reset()
        assertTrue(host.published.isEmpty())
        assertEquals(listOf(0, 1, 2, 3, 4), host.released.sorted())
        assertEquals(listOf(0, 1), host.discarded)
        atlas.prepare(0, 0, 1.0, 16)
        assertEquals(setOf(0L, 1, 2, 3), host.published.keys)
        atlas.close()
        atlas.close()
        assertEquals(host.snapshots.indices.toList(), host.released.sorted())
        assertEquals(listOf(0, 1, 0, 1), host.discarded)
        with(atlas.statistics) { assertEquals(preparedImages, retiredImages) }
        assertFailsWith<IllegalStateException> { atlas.prepare(0, 0, 1.0, 16) }
    }
}
