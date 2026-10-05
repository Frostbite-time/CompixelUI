package dev.compixel.forge

import dev.compixel.forge.render.configuredRenderBackend
import kotlin.test.*
import org.junit.jupiter.api.Test

class AdapterContractsTest {
    @Test
    fun unsupportedBackendsFailWithoutFallback() {
        assertEquals(dev.compixel.render.RenderBackend.OPENGL, configuredRenderBackend("auto"))
        assertEquals(dev.compixel.render.RenderBackend.OPENGL, configuredRenderBackend("opengl"))
        assertEquals(dev.compixel.render.RenderBackend.CPU_RASTER, configuredRenderBackend("cpu"))
        assertFailsWith<IllegalStateException> { configuredRenderBackend("vulkan") }
        assertFailsWith<IllegalStateException> { configuredRenderBackend("unknown") }
    }
}
