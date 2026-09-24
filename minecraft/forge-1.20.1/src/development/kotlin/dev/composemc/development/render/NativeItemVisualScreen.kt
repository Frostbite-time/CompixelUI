package dev.composemc.development.render

import androidx.compose.ui.geometry.Rect
import dev.composemc.bridge.ComposeThread
import dev.composemc.forge.IconRefresh
import dev.composemc.forge.ItemIcon
import dev.composemc.forge.MinecraftItemIcon
import dev.composemc.forge.ForgeComposeScreen
import dev.composemc.testing.suite.ScreenPixels
import dev.composemc.testing.ui.NATIVE_VISUAL_RED
import dev.composemc.testing.ui.NativeItemVisualModel
import dev.composemc.testing.ui.NativeItemVisualScene
import dev.composemc.testing.ui.verifyNativeItemVisualPixels
import net.minecraft.network.chat.Component

internal class NativeItemVisualScreen(
    val model: NativeItemVisualModel = ComposeThread.call { NativeItemVisualModel() },
    private val icon: ItemIcon = ItemIcon.drawn("native visual acceptance",
        { graphics -> graphics.fill(0, 0, 16, 16, NATIVE_VISUAL_RED) }, IconRefresh.STATIC),
) : ForgeComposeScreen(Component.literal("Native item visual acceptance"), content = {
    NativeItemVisualScene(model) { modifier -> MinecraftItemIcon(icon, modifier) }
}) {
    override fun isUiWindowFocused() = dev.composemc.development.SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    fun bounds(): Map<String, Rect> = ComposeThread.call { model.bounds() }

    fun verifyPixels(pixels: ScreenPixels, bounds: Map<String, Rect>) {
        verifyNativeItemVisualPixels(bounds, pixels.width, pixels.height, pixels::argb)
    }
}
