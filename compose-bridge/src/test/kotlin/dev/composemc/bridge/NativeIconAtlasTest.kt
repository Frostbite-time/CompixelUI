package dev.composemc.bridge

import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import kotlin.test.*

class NativeIconAtlasTest {
    private class Icon(val id: Long, val refresh: NativeIconRefresh = NativeIconRefresh.STATIC)

    private class Host(override val immediate: Boolean = true) : NativeIconAtlas.Host<Icon> {
        val draws = mutableListOf<Pair<Int, List<NativeIconAtlas.Placement<Icon>>>>()
        val snapshots = mutableListOf<Image>()
        /** Snapshot indices; closed Skia images compare equal, so identity is tracked here. */
        val released = mutableListOf<Int>()
        val published = HashMap<Long, NativeImageRegion>()
        val appearances = HashMap<Long, Any>()
        val appearanceCalls = HashMap<Long, Int>()
        var copyReady = true
        override fun id(icon: Icon) = icon.id
        override fun refresh(icon: Icon) = icon.refresh
        override fun appearance(icon: Icon): Any? {
            appearanceCalls.merge(icon.id, 1, Int::plus)
            return appearances[icon.id]
        }
        override fun draw(buffer: Int, icons: List<NativeIconAtlas.Placement<Icon>>) { draws += buffer to icons }
        override fun snapshot(buffer: Int): Image? {
            if (!copyReady) return null
            val surface = Surface.makeRasterN32Premul(4, 4)
            return try { surface.makeImageSnapshot().also { snapshots += it } } finally { surface.close() }
        }
        override fun release(image: Image) {
            val index = snapshots.indexOfFirst { it === image }
            check(index >= 0 && index !in released) { "Unknown or repeated release" }
            released += index
        }
        override fun publish(regions: Map<Long, NativeImageRegion>, removed: Set<Long>) {
            check(EventQueue.isDispatchThread())
            removed.forEach(published::remove)
            published.putAll(regions)
        }
        override fun clear() { check(EventQueue.isDispatchThread()); published.clear() }
        fun drawnIds(draw: Int) = draws[draw].second.map { it.icon.id }
        fun region(id: Long) = published.getValue(id).source.let { listOf(it.left, it.top, it.right, it.bottom) }
    }

    private fun icons(count: Int, refresh: NativeIconRefresh = NativeIconRefresh.STATIC) = List(count) { Icon(it.toLong(), refresh) }

    @Test fun layoutFitsTheBudgetAndScalesRegionsToPagePixels() {
        val atlas = NativeIconAtlas(128, 64, 64, Host())
        assertEquals(64, atlas.pageCapacity)
        assertEquals(144 to 144, atlas.guiWidth to atlas.guiHeight)
        assertEquals(576 to 576, atlas.width to atlas.height)
        assertEquals(1, atlas.buffers)
        val small = NativeIconAtlas(10, 64, 20, Host(immediate = false))
        assertEquals(10, small.pageCapacity)
        assertEquals(72 to 54, small.guiWidth to small.guiHeight)
        assertEquals(90 to 68, small.width to small.height)
        assertEquals(2, small.buffers)
    }

    @Test fun everyVisibleIconGetsItsRegionAndStaticPagesAreDrawnOnce() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, 64, host)
        atlas.recorded(icons(6))
        assertTrue(atlas.prepare(0, 0, 2.0))
        assertEquals(listOf(0L, 1, 2, 3), host.drawnIds(0))
        assertEquals(listOf(0 to 0, 18 to 0, 0 to 18, 18 to 18), host.draws[0].second.map { it.x to it.y })
        assertTrue(atlas.prepare(0, 0, 2.0))
        assertEquals(listOf(4L, 5), host.drawnIds(1))
        assertFalse(atlas.prepare(0, 0, 2.0))
        assertEquals(2, host.draws.size)

        assertEquals(listOf(72f, 72f, 136f, 136f), host.region(3))
        assertSame(host.snapshots[0], host.published.getValue(3).image)
        assertEquals(listOf(72f, 0f, 136f, 64f), host.region(5))
        assertSame(host.snapshots[1], host.published.getValue(5).image)
        assertEquals(NativeIconAtlas.Statistics(6, 6, 0, 2, 0, 0, 0), atlas.statistics)
        atlas.close()
    }

    @Test fun animatedPagesRotateSoNoIconStarves() {
        val host = Host()
        val atlas = NativeIconAtlas(16, 4, 16, host)
        atlas.recorded(icons(16, NativeIconRefresh.FRAME))
        repeat(12) { atlas.prepare(0, 0, 1.0) }
        val drawn = host.draws.flatMap { (_, icons) -> icons.map { it.icon.id } }.groupingBy { it }.eachCount()
        assertEquals((0L until 16).associateWith { 3 }, drawn)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3), host.draws.map { it.second.first().icon.id.toInt() / 4 })
        assertEquals(32, atlas.statistics.animationRefreshes)
        assertEquals(16, atlas.statistics.dynamicVariants)
        atlas.close()
    }

    @Test fun onlyHiddenIconsAreEvictedAndUnpublished() {
        val host = Host()
        val atlas = NativeIconAtlas(4, 4, 16, host)
        val (a, b, c, d) = icons(4)
        val e = Icon(4)
        atlas.recorded(listOf(a, b, c, d))
        atlas.prepare(0, 0, 1.0)
        atlas.recorded(listOf(e, b, c, d))
        assertTrue(atlas.prepare(0, 0, 1.0))
        assertEquals(listOf(4L, 1, 2, 3), host.drawnIds(1))
        assertEquals(setOf(1L, 2, 3, 4), host.published.keys)

        val pinned = NativeIconAtlas(2, 2, 16, Host())
        pinned.recorded(icons(3))
        pinned.prepare(0, 0, 1.0)
        assertEquals(NativeIconAtlas.Statistics(3, 2, 1, 1, 0, 0, 0), pinned.statistics)
        atlas.close()
        pinned.close()
    }

    @Test fun deferredHostsPublishOnTheNextFrameAndAlternateBuffers() {
        val host = Host(immediate = false)
        val atlas = NativeIconAtlas(4, 2, 16, host)
        atlas.recorded(icons(4, NativeIconRefresh.FRAME))
        assertFalse(atlas.prepare(0, 0, 1.0))
        assertTrue(host.published.isEmpty())
        assertTrue(atlas.prepare(0, 0, 1.0))
        assertEquals(setOf(0L, 1), host.published.keys)
        atlas.prepare(0, 0, 1.0)
        assertEquals(setOf(0L, 1, 2, 3), host.published.keys)
        assertEquals(listOf(0, 1, 0), host.draws.map { it.first })
        atlas.close()
    }

    @Test fun deferredCopiesHoldTheirBufferUntilComplete() {
        val host = Host(immediate = false)
        val atlas = NativeIconAtlas(4, 2, 16, host)
        atlas.recorded(icons(4))
        assertFalse(atlas.prepare(0, 0, 1.0))
        host.copyReady = false
        repeat(3) { assertFalse(atlas.prepare(0, 0, 1.0)) }
        assertEquals(1, host.draws.size)
        assertTrue(host.published.isEmpty())
        host.copyReady = true
        assertTrue(atlas.prepare(0, 0, 1.0))
        assertEquals(setOf(0L, 1), host.published.keys)
        assertEquals(listOf(0, 1), host.draws.map { it.first })
        assertTrue(atlas.prepare(0, 0, 1.0))
        assertEquals(setOf(0L, 1, 2, 3), host.published.keys)
        atlas.close()
    }

    @Test fun changedAppearancesRedrawTheirPageAndAreComparedOncePerTick() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, 16, host)
        val changing = icons(8, NativeIconRefresh.ON_CHANGE)
        changing.forEach { host.appearances[it.id] = "still" }
        atlas.recorded(changing)
        repeat(2) { assertTrue(atlas.prepare(0, 0, 1.0)) }
        assertFalse(atlas.prepare(0, 1, 1.0))
        host.appearances[5] = "moved"
        // The tick that already compared its appearances notices the change only on the next tick.
        assertFalse(atlas.prepare(0, 1, 1.0))
        assertTrue(atlas.prepare(0, 2, 1.0))
        assertEquals(listOf(4L, 5, 6, 7), host.drawnIds(2))
        assertFalse(atlas.prepare(0, 3, 1.0))
        assertEquals(3, host.draws.size)
        assertEquals((0L until 8).associateWith { 4 }, host.appearanceCalls)
        atlas.close()
    }

    @Test fun dueRulesFollowTicksIntervalsAndGuiScale() {
        val host = Host()
        val atlas = NativeIconAtlas(3, 1, 16, host)
        val ticking = Icon(0, NativeIconRefresh.GAME_TICK)
        val timed = Icon(1, NativeIconRefresh.every(100))
        val still = Icon(2)
        atlas.recorded(listOf(ticking, timed, still))
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

    @Test fun resetAndCloseReleaseEveryPageImage() {
        val host = Host()
        val atlas = NativeIconAtlas(8, 4, 16, host)
        atlas.recorded(icons(8, NativeIconRefresh.FRAME))
        repeat(5) { atlas.prepare(0, 0, 1.0) }
        assertEquals(5, host.snapshots.size)
        assertEquals(listOf(0, 1, 2), host.released)
        atlas.reset()
        assertTrue(host.published.isEmpty())
        assertEquals(listOf(0, 1, 2, 3, 4), host.released.sorted())
        atlas.prepare(0, 0, 1.0)
        assertEquals(setOf(0L, 1, 2, 3), host.published.keys)
        atlas.close()
        atlas.close()
        assertEquals(host.snapshots.indices.toList(), host.released.sorted())
        with(atlas.statistics) { assertEquals(preparedImages, retiredImages) }
        assertFailsWith<IllegalStateException> { atlas.prepare(0, 0, 1.0) }
    }
}
