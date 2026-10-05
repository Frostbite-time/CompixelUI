package dev.compixel.forge

import dev.compixel.forge.render.configuredRenderBackend
import kotlin.test.*
import org.junit.jupiter.api.Test

class AdapterContractsTest {
    @Test
    fun supportedBackendsAreExplicitWithoutFallback() {
        assertEquals(dev.compixel.render.RenderBackend.OPENGL, configuredRenderBackend("opengl"))
        assertEquals(dev.compixel.render.RenderBackend.CPU_RASTER, configuredRenderBackend("cpu"))
        assertEquals(dev.compixel.render.RenderBackend.VULKAN, configuredRenderBackend("vulkan"))
        assertFailsWith<IllegalStateException> { configuredRenderBackend("unknown") }
    }
}
