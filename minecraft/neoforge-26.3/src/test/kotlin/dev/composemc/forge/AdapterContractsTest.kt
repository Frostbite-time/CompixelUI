package dev.composemc.forge

import org.junit.jupiter.api.Test
import kotlin.test.*

class AdapterContractsTest {
    @Test fun supportedBackendsAreExplicitWithoutFallback() {
        assertEquals(dev.composemc.render.RenderBackend.OPENGL, configuredRenderBackend("opengl"))
        assertEquals(dev.composemc.render.RenderBackend.CPU_RASTER, configuredRenderBackend("cpu"))
        assertEquals(dev.composemc.render.RenderBackend.VULKAN, configuredRenderBackend("vulkan"))
        assertFailsWith<IllegalStateException> { configuredRenderBackend("unknown") }
    }
    @Test fun `GUI input conversion does not use Compose density`() {
        val metrics = ScreenMetrics(1280, 960, 320, 240, 4f, 1f)
        assertEquals(4f, metrics.viewport.density)
        assertEquals(400f, metrics.pixelX(100.0))
        assertEquals(200f, metrics.pixelY(50.0))
    }
    @Test fun `rounded GUI dimensions map to framebuffer edges`() {
        val metrics = ScreenMetrics(1001, 721, 334, 241, 3f, 1f)
        assertEquals(1001f, metrics.pixelX(334.0))
        assertEquals(721f, metrics.pixelY(241.0))
    }
    @Test fun `changing dp scale only changes layout density`() {
        val a = ScreenMetrics(1280, 960, 320, 240, 4f, 1f)
        val b = a.copy(guiUnitsPerDp = 0.5f)
        assertEquals(a.pixelX(73.5), b.pixelX(73.5))
        assertNotEquals(a.viewport.density, b.viewport.density)
    }
    @Test fun `minimum density keeps small-window text readable without changing input coordinates`() {
        val original = ScreenMetrics(480, 720, 480, 720, 1f, 1f)
        val readable = original.copy(minimumUiDensity = 1f)
        assertEquals(1f, original.viewport.density)
        assertEquals(1f, readable.viewport.density)
        assertEquals(original.pixelX(120.0), readable.pixelX(120.0))
        assertEquals(original.pixelY(360.0), readable.pixelY(360.0))
    }
    @Test fun `invalid metrics fail immediately`() {
        assertFailsWith<IllegalArgumentException> { ScreenMetrics(100, 100, 0, 100, 1f, 1f) }
        assertFailsWith<IllegalArgumentException> { ScreenMetrics(100, 100, 100, 100, 1f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ScreenMetrics(0, 100, 100, 100, 1f, 1f) }
    }
    @Test fun `supplementary Unicode is committed atomically`() {
        val input = CommittedCharacters()
        assertNull(input.accept('\uD83D'))
        assertEquals("\uD83D\uDE00", input.accept('\uDE00'))
    }
    @Test fun `focus loss discards an incomplete surrogate`() {
        val input = CommittedCharacters()
        input.accept('\uD83D')
        input.reset()
        assertNull(input.accept('\uDE00'))
        assertEquals("a", input.accept('a'))
    }
    @Test fun `control characters and lone surrogates are not committed`() {
        val input = CommittedCharacters()
        assertNull(input.accept('\n'))
        assertNull(input.accept('\uDE00'))
        assertEquals("\u4e2d", input.accept('\u4e2d'))
    }
    @Test fun `clipboard mailbox makes pending writes visible without GLFW on EDT`() {
        val clipboard = ClipboardMailbox()
        clipboard.refresh("old")
        clipboard.writeText("new")
        clipboard.refresh("stale OS snapshot")
        assertEquals("new", clipboard.readText())
        assertEquals("new", clipboard.takeWrite())
        assertEquals("new", clipboard.readText())
        assertNull(clipboard.takeWrite())
    }
    @Test fun `clipboard supports clearing text`() {
        val clipboard = ClipboardMailbox()
        clipboard.refresh("old")
        clipboard.writeText("")
        assertEquals("", clipboard.takeWrite())
        assertEquals("", clipboard.readText())
    }
}
