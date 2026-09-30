package dev.compixel.forge.item

import java.util.function.Consumer
import kotlin.test.*
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.world.phys.AABB
import org.joml.Matrix3x2f
import org.junit.jupiter.api.Test

class NativeItemGuiRenderStateTest {
    // addItem/addGuiElement only additionally draw optional debug rectangles, which initialize the FML environment.
    // Use the real node selection and storage without that launcher-only debug path in these headless tests.
    private fun addItem(state: NativeItemGuiRenderState, item: GuiItemRenderState) {
        selectNode(state, item)
        state.itemModelIdentities.let { (it as MutableSet<Any>).add(item.itemStackRenderState().modelIdentity) }
        val current = GuiRenderState::class.java.getDeclaredField("current").apply { isAccessible = true }.get(state)
        current.javaClass.getDeclaredMethod("addItem", GuiItemRenderState::class.java).apply { isAccessible = true }
            .invoke(current, item)
    }

    private fun selectNode(state: GuiRenderState, area: net.minecraft.client.renderer.state.gui.ScreenArea) {
        val select = GuiRenderState::class.java
            .getDeclaredMethod("findAppropriateNode", net.minecraft.client.renderer.state.gui.ScreenArea::class.java)
            .apply { isAccessible = true }
        assertEquals(true, select.invoke(state, area))
    }

    private fun item(id: String, oversized: Boolean = false): GuiItemRenderState {
        val model =
            if (oversized) object : TrackingItemStackRenderState() {
                override fun isOversizedInGui() = true
                override fun getModelBoundingBox() = AABB(-1.0, -1.0, -1.0, 1.0, 1.0, 1.0)
            }
            else TrackingItemStackRenderState()
        model.appendModelIdentityElement(id)
        return GuiItemRenderState(Matrix3x2f(), model, 0, 0, null)
    }

    private fun blit(item: GuiItemRenderState, color: Int, pixels: Int = 70) =
        BlitRenderState(
            RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
            TextureSetup.noTexture(),
            item.pose(), item.x(), item.y(), item.x() + 16, item.y() + 16,
            0f, pixels / 256f, 1f, 1f - pixels / 256f, color, item.scissorArea(),
        )

    private fun elements(state: GuiRenderState): List<BlitRenderState> =
        buildList {
            state.forEachElement({ if (it is BlitRenderState) add(it) }, GuiRenderState.TraverseRange.ALL)
        }

    @Test
    fun `ordinary models bypass integer-scale caches at every demanded size`() {
        for (pixels in listOf(56, 70, 80, 84, 98, 140)) {
            val state = NativeItemGuiRenderState()
            val first = item("first")
            val second = item("second")
            addItem(state, first)
            addItem(state, second)
            var preparations = 0
            state.prepareItems(pixels) { size, models ->
                preparations++
                assertEquals(pixels, size, "Rasterization must not round up to a multiple of sixteen")
                assertEquals(setOf(first.itemStackRenderState().modelIdentity, second.itemStackRenderState().modelIdentity), models)
                return@prepareItems { item -> blit(item, -1, size) }
            }
            assertEquals(1, preparations)
            assertTrue(state.itemModelIdentities.isEmpty(), "GuiRenderer must not allocate or sample its integer-size cache")
            var vanillaItems = 0
            state.forEachItem(Consumer { vanillaItems++ })
            assertEquals(0, vanillaItems, "Ordinary models must not be drawn twice")
            assertEquals(2, elements(state).size)
            elements(state).forEach {
                assertEquals(pixels.toFloat(), (it.u1() - it.u0()) * 256)
                assertEquals(pixels.toFloat(), (it.v0() - it.v1()) * 256)
            }
        }
    }

    @Test
    fun `converted item blits remain below and above intervening GUI content`() {
        val state = NativeItemGuiRenderState()
        val first = item("first")
        val second = item("second")
        addItem(state, first)
        val marker = blit(first, 2)
        selectNode(state, marker)
        state.addBlitToCurrentLayer(marker)
        addItem(state, second)
        state.prepareItems(70) { _, _ ->
            { item -> blit(item, if (item === first) 1 else 3) }
        }
        assertEquals(listOf(1, 2, 3), elements(state).map { it.color() })
    }

    @Test
    fun `oversized models keep their native renderer while ordinary models use exact pixels`() {
        val state = NativeItemGuiRenderState()
        val ordinary = item("ordinary")
        val oversized = item("oversized", oversized = true)
        assertNotNull(oversized.oversizedItemBounds())
        addItem(state, ordinary)
        addItem(state, oversized)
        state.prepareItems(70) { size, models ->
            assertEquals(70, size)
            assertEquals(setOf(ordinary.itemStackRenderState().modelIdentity), models)
            return@prepareItems { item -> blit(item, -1, size) }
        }
        assertEquals(setOf(oversized.itemStackRenderState().modelIdentity), state.itemModelIdentities)
        val remaining = mutableListOf<GuiItemRenderState>()
        state.forEachItem(Consumer { remaining += it })
        assertEquals(listOf(oversized), remaining)
        assertEquals(1, elements(state).size)
    }

    @Test
    fun `reset restores standard native rendering for tooltip captures`() {
        val state = NativeItemGuiRenderState()
        val icon = item("icon")
        addItem(state, icon)
        state.prepareItems(70) { _, _ -> { item -> blit(item, -1) } }
        state.reset()
        val tooltipItem = item("tooltip")
        addItem(state, tooltipItem)
        assertEquals(setOf(tooltipItem.itemStackRenderState().modelIdentity), state.itemModelIdentities)
        val items = mutableListOf<GuiItemRenderState>()
        state.forEachItem(Consumer { items += it })
        assertEquals(listOf(tooltipItem), items)
        assertTrue(elements(state).isEmpty())
    }
}
