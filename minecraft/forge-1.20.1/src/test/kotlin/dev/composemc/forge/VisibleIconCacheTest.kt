package dev.composemc.forge

import org.junit.jupiter.api.Test
import kotlin.test.*

class VisibleIconCacheTest {
    @Test fun scrollingEvictsOnlyOffscreenEntriesAndClosesEverything() {
        val retired = mutableListOf<String>()
        val cache = VisibleIconCache<Int, String>(2) { _, value -> retired += value }
        cache.visible = setOf(1, 2)
        cache.put(1, "stone")
        cache.put(2, "sword")
        assertFalse(cache.canStore(3))
        assertEquals(2, cache.size)
        cache.visible = setOf(2, 3)
        cache.put(3, "chest")
        assertEquals(listOf("stone"), retired)
        assertEquals("sword", cache[2])
        cache.close()
        cache.close()
        assertEquals(setOf("stone", "sword", "chest"), retired.toSet())
        assertEquals(3, retired.size)
        assertEquals(0, cache.size)
    }

    @Test fun animatedReplacementRetiresThePreviousImageExactlyOnce() {
        val retired = mutableListOf<String>()
        val cache = VisibleIconCache<Int, String>(1) { _, value -> retired += value }
        cache.visible = setOf(1)
        cache.put(1, "frame-1")
        cache.put(1, "frame-2")
        assertEquals(listOf("frame-1"), retired)
        assertEquals("frame-2", cache[1])
        cache.close()
        assertEquals(listOf("frame-1", "frame-2"), retired)
    }
}
