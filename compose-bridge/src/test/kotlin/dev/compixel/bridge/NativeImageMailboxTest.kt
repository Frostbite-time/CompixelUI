package dev.compixel.bridge

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlin.test.*
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test

class NativeImageMailboxTest {
    @Test
    fun sharedRectanglesRetainTheirDemandsAndChooseTheNearestPublishedSize() = ComposeThread.call {
        Surface.makeRasterN32Premul(16, 16).use { surface ->
            surface.makeImageSnapshot().use { image ->
                val mailbox = NativeImageMailbox<Long> { it }
                val wide = NativeImageAtlas.Size(200, 40)
                val tall = NativeImageAtlas.Size(40, 200)
                val requested = NativeImageAtlas.Size(48, 180)
                val wideKey = NativeImageAtlas.Variant(1, wide)
                val tallKey = NativeImageAtlas.Variant(1, tall)
                val wideRegion = NativeImageRegion(image, Rect.makeWH(12f, 3f))
                val tallRegion = NativeImageRegion(image, Rect.makeWH(3f, 12f))
                mailbox.retain(1, requested)
                mailbox.retain(1, requested)
                mailbox.publish(
                    mapOf(wideKey to wideRegion, tallKey to tallRegion),
                    setOf(wideKey, tallKey),
                    emptySet(),
                )
                assertSame(tallRegion, mailbox.request(1, requested))
                mailbox.release(1, requested)
                assertEquals(listOf(requested), mailbox.activeRequests().map { it.size })
                mailbox.publish(emptyMap(), emptySet(), setOf(tallKey))
                assertSame(wideRegion, mailbox.request(1, requested))
                mailbox.clear()
                assertNull(mailbox.request(1, requested))
                assertEquals(1, mailbox.activeRequests().size)
                mailbox.release(1, requested)
                assertTrue(mailbox.activeRequests().isEmpty())
            }
        }
    }

    @Test
    fun unchangedCellsMoveToNewSnapshotsWithoutInvalidatingTheirComposeDrawing() = ComposeThread.call {
        Surface.makeRasterN32Premul(16, 16).use { surface ->
            surface.makeImageSnapshot().use { first ->
                surface.makeImageSnapshot().use { next ->
                    val mailbox = NativeImageMailbox<Long> { it }
                    val size = NativeImageAtlas.Size(8, 4)
                    val key = NativeImageAtlas.Variant(1, size)
                    mailbox.retain(1, size)
                    mailbox.publish(mapOf(key to NativeImageRegion(first, Rect.makeWH(8f, 4f))), setOf(key), emptySet())
                    Snapshot.sendApplyNotifications()
                    val observer = SnapshotStateObserver { it() }
                    var invalidations = 0
                    observer.start()
                    try {
                        observer.observeReads(Any(), { _: Any -> invalidations++ }) { mailbox.request(1, size) }
                        mailbox.publish(
                            mapOf(key to NativeImageRegion(next, Rect.makeWH(8f, 4f))),
                            emptySet(),
                            emptySet(),
                        )
                        Snapshot.sendApplyNotifications()
                        assertSame(next, mailbox.request(1, size)?.image)
                        assertEquals(0, invalidations)
                        mailbox.publish(emptyMap(), emptySet(), setOf(key))
                        Snapshot.sendApplyNotifications()
                        assertEquals(1, invalidations)
                        assertNull(mailbox.request(1, size))
                    } finally {
                        observer.stop()
                        observer.clear()
                        mailbox.release(1, size)
                    }
                }
            }
        }
    }
}
