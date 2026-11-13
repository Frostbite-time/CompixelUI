package dev.compixel.host.resources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.compixel.host.UiSession
import dev.compixel.platform.ResourceSource
import dev.compixel.platform.Viewport
import dev.compixel.render.RecordedFrame
import dev.compixel.ui.resources.LocalResources
import dev.compixel.ui.resources.ResourceType
import dev.compixel.ui.resources.UiResources
import dev.compixel.ui.resources.painterResource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.jupiter.api.Test

class UiResourcesTest {
    @Test
    fun differentResourceTypesCanShareAPathAndReloadTogether() {
        var bytes = "first".toByteArray()
        var reads = 0
        val resources =
            UiResources(
                ResourceSource {
                    reads++
                    bytes
                }
            )
        val text = ResourceType<String> { _, data -> data.toString(Charsets.UTF_8) }
        val size = ResourceType<Int> { _, data -> data.size }
        var observed = ""
        var length = 0
        session(resources) {
                val string = resources.load("example:data", text).getOrThrow()
                val count = resources.load("example:data", size).getOrThrow()
                val shared = resources.load("example:data", text).getOrThrow()
                SideEffect {
                    assertEquals(string, shared)
                    observed = string
                    length = count
                }
            }
            .use { session ->
                session.frame(1)?.close()
                assertEquals("first", observed)
                assertEquals(5, length)
                assertEquals(2, reads)
                bytes = "reloaded".toByteArray()
                resources.reload()
                session.frame(2)?.close()
                assertEquals("reloaded", observed)
                assertEquals(8, length)
                assertEquals(4, reads)
                assertEquals(2, resources.cachedResources)
            }
        assertEquals(0, resources.cachedResources)
    }

    @Composable
    private fun picture(path: String) {
        Image(painterResource(path), null, Modifier.size(16.dp), contentScale = ContentScale.FillBounds)
    }

    private fun session(resources: UiResources, content: @Composable () -> Unit) =
        UiSession(Viewport(32, 16)) { CompositionLocalProvider(LocalResources provides resources, content = content) }

    @Test
    fun twoUsersShareOneDecodedImageAndClosingReleasesTheCache() {
        var reads = 0
        val resources =
            UiResources(
                ResourceSource {
                    reads++
                    png(RED)
                }
            )
        session(resources) {
                Row {
                    picture("example:red.png")
                    picture("example:red.png")
                }
            }
            .use { session ->
                session.frame(1)!!.use { frame ->
                    assertEquals(RED, pixels(frame).getRGB(8, 8))
                    assertEquals(RED, pixels(frame).getRGB(24, 8))
                }
                repeat(3) { session.frame(2L + it)?.close() }
                assertEquals(1, reads)
                assertEquals(1, resources.cachedResources)
            }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun reloadChangesPixelsAndIntrinsicSizeWithoutReplacingRememberedStateOrOldFrames() {
        var bytes = png(RED)
        val resources = UiResources(ResourceSource { bytes })
        var creations = 0
        var draft = ""
        var edit: (String) -> Unit = {}
        var intrinsic = Size.Zero
        session(resources) {
                var text by remember {
                    creations++
                    mutableStateOf("initial")
                }
                val shown = text
                val painter = painterResource("example:panel.png")
                SideEffect {
                    draft = shown
                    edit = { text = it }
                    intrinsic = painter.intrinsicSize
                }
                Image(painter, null, Modifier.size(16.dp), contentScale = ContentScale.FillBounds)
            }
            .use { session ->
                session.frame(1)!!.use { original ->
                    edit("unsaved draft")
                    session.frame(2)?.close()
                    bytes = png(GREEN, 4, 8)
                    resources.reload()
                    session.frame(3)!!.use { assertEquals(GREEN, pixels(it).getRGB(8, 8)) }
                    assertEquals(Size(4f, 8f), intrinsic)
                    assertEquals("unsaved draft", draft)
                    assertEquals(1, creations)
                    assertEquals(
                        RED,
                        pixels(original).getRGB(8, 8),
                        "Reload invalidated a picture that still owned its pixels",
                    )
                    assertEquals(1, resources.cachedResources, "An old lease removed the reloaded entry")
                }
            }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun anImageStaysCachedUntilItsLastUserLeavesTheComposition() {
        var reads = 0
        val count = mutableIntStateOf(2)
        val resources =
            UiResources(
                ResourceSource {
                    reads++
                    png(RED)
                }
            )
        session(resources) { Row { repeat(count.intValue) { picture("example:red.png") } } }
            .use { session ->
                session.frame(1)?.close()
                count.intValue = 1
                session.frame(2)?.close()
                assertEquals(1, resources.cachedResources)
                assertEquals(1, reads)
                count.intValue = 0
                session.frame(3)?.close()
                assertEquals(0, resources.cachedResources)
                count.intValue = 1
                session.frame(4)?.close()
                assertEquals(2, reads)
            }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun missingAndBrokenFilesShowACheckerboardAndCanRecoverOnReload() {
        val files = mutableMapOf("example:broken.png" to byteArrayOf(1, 2, 3))
        val errors = mutableListOf<String>()
        val resources = UiResources(ResourceSource { files[it] }, onError = { path, _ -> errors += path })
        session(resources) {
                Row {
                    picture("example:missing.png")
                    picture("example:broken.png")
                }
            }
            .use { session ->
                session.frame(1)!!.use { frame ->
                    val image = pixels(frame)
                    assertEquals(MAGENTA, image.getRGB(2, 2))
                    assertEquals(BLACK, image.getRGB(12, 2))
                    assertEquals(MAGENTA, image.getRGB(18, 2))
                }
                repeat(3) { session.frame(2L + it)?.close() }
                assertEquals(listOf("example:missing.png", "example:broken.png"), errors)
                files["example:missing.png"] = png(RED)
                files["example:broken.png"] = png(GREEN)
                resources.reload()
                session.frame(5)!!.use { frame ->
                    val image = pixels(frame)
                    assertEquals(RED, image.getRGB(8, 8))
                    assertEquals(GREEN, image.getRGB(24, 8))
                }
            }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun anAbandonedCompositionDoesNotKeepItsImagesCached() {
        val resources = UiResources(ResourceSource { png(RED) })
        assertFailsWith<IllegalStateException> {
            session(resources) {
                    picture("example:red.png")
                    error("Content failed before it could commit")
                }
                .close()
        }
        assertEquals(0, resources.cachedResources)
    }

    @Test
    fun reloadBelongsToTheUiThread() {
        val resources = UiResources(ResourceSource { png(RED) })
        var failure: Throwable? = null
        Thread { failure = runCatching { resources.reload() }.exceptionOrNull() }
            .apply {
                start()
                join()
            }
        assertIs<IllegalStateException>(failure)
    }

    private fun pixels(frame: RecordedFrame) = ImageIO.read(ByteArrayInputStream(frame.encodePng()))

    private fun png(color: Int, width: Int = 2, height: Int = 2): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color)
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val MAGENTA = 0xFFFF00FF.toInt()
        val BLACK = 0xFF000000.toInt()
    }
}
