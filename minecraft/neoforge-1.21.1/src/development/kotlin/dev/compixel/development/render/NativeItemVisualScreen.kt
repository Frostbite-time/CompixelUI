package dev.compixel.development.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.drawing.NativeRefresh
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.testing.suite.ScreenPixels
import dev.compixel.testing.ui.NATIVE_PARTIAL_STILL
import dev.compixel.testing.ui.NATIVE_PARTIAL_TICKING
import dev.compixel.testing.ui.NATIVE_VISUAL_RED
import dev.compixel.testing.ui.NativeItemPartialScene
import dev.compixel.testing.ui.NativeItemVisualModel
import dev.compixel.testing.ui.NativeItemVisualScene
import dev.compixel.testing.ui.verifyNativeItemPartialPixels
import dev.compixel.testing.ui.verifyNativeItemVisualPixels
import java.util.concurrent.atomic.AtomicInteger
import net.minecraft.network.chat.Component

internal class NativeItemVisualScreen(
    val model: NativeItemVisualModel = ComposeThread.call { NativeItemVisualModel() },
    private val icon: ItemIcon =
        ItemIcon.drawn(
            "native visual acceptance",
            { graphics -> graphics.fill(0, 0, 16, 16, NATIVE_VISUAL_RED) },
            NativeRefresh.STATIC,
        ),
) :
    ComposeScreen(
        Component.literal("Native item visual acceptance"),
        content = {
            NativeItemVisualScene(model) { modifier -> MinecraftItemIcon(icon, modifier) }
        },
    ) {
    override fun isUiWindowFocused() = dev.compixel.development.SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    fun bounds(): Map<String, Rect> = ComposeThread.call { model.bounds() }

    fun verifyPixels(pixels: ScreenPixels, bounds: Map<String, Rect>) {
        verifyNativeItemVisualPixels(bounds, pixels.width, pixels.height, pixels::argb)
    }
}

/** One 2x2 atlas page: an icon drawn again every game tick, in the other color each time, among three still ones. */
internal class NativeItemPartialScreen(
    val model: NativeItemVisualModel = ComposeThread.call { NativeItemVisualModel() },
    private val icons: List<ItemIcon> = partialIcons(),
) :
    ComposeScreen(
        Component.literal("Native partial redraw acceptance"),
        nativeItemOptions = NativeItemOptions(preparationsPerFrame = 4),
        content = {
            NativeItemPartialScene(
                model,
                icons.map { icon -> @Composable { modifier: Modifier -> MinecraftItemIcon(icon, modifier) } },
            )
        },
    ) {
    override fun isUiWindowFocused() = dev.compixel.development.SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    fun bounds(): Map<String, Rect> = ComposeThread.call { model.bounds() }

    fun verifyPixels(pixels: ScreenPixels, bounds: Map<String, Rect>) {
        verifyNativeItemPartialPixels(bounds, pixels.width, pixels.height, pixels::argb)
    }
}

private fun partialIcons(): List<ItemIcon> {
    val drawings = AtomicInteger()
    val ticking =
        ItemIcon.drawn(
            "native partial ticking",
            { graphics -> graphics.fill(0, 0, 16, 16, NATIVE_PARTIAL_TICKING[drawings.getAndIncrement() % 2]) },
            NativeRefresh.GAME_TICK,
        )
    return listOf(ticking) +
        NATIVE_PARTIAL_STILL.mapIndexed { index, color ->
            ItemIcon.drawn(
                "native partial still $index",
                { graphics -> graphics.fill(0, 0, 16, 16, color) },
                NativeRefresh.STATIC,
            )
        }
}
