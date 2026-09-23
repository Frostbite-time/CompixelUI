package dev.composemc.development.render

import com.mojang.blaze3d.platform.NativeImage
import dev.composemc.bridge.ComposeThread
import dev.composemc.forge.ForgeComposeScreen
import dev.composemc.forge.IconRefresh
import dev.composemc.forge.ItemIcon
import dev.composemc.forge.MinecraftItemIcon
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
    override fun isUiWindowFocused() = true

    fun verifyPixels(image: NativeImage) {
        val bounds = ComposeThread.call { model.bounds() }
        verifyNativeItemVisualPixels(bounds, image.width, image.height, image::getPixelRGBA)
    }
}
