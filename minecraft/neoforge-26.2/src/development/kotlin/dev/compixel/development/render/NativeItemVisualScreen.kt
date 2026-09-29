package dev.compixel.development.render

import androidx.compose.ui.geometry.Rect
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.item.IconRefresh
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.testing.suite.ScreenPixels
import dev.compixel.testing.ui.NATIVE_VISUAL_RED
import dev.compixel.testing.ui.NativeItemVisualModel
import dev.compixel.testing.ui.NativeItemVisualScene
import dev.compixel.testing.ui.verifyNativeItemVisualPixels
import net.minecraft.network.chat.Component

internal class NativeItemVisualScreen(
    val model: NativeItemVisualModel = ComposeThread.call { NativeItemVisualModel() },
    private val icon: ItemIcon =
        ItemIcon.drawn(
            "native visual acceptance",
            { graphics -> graphics.fill(0, 0, 16, 16, NATIVE_VISUAL_RED) },
            IconRefresh.STATIC,
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
