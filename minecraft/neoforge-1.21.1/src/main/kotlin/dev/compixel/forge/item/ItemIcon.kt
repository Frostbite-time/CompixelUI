package dev.compixel.forge.item

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.NativeImageAtlas
import dev.compixel.bridge.NativeImageContent
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.forge.drawing.NativeRefresh
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/** An owned ItemStack copy. Create snapshots on the game thread, then pass the handle to Compose. */
class ItemIcon
private constructor(
    internal val id: Long,
    internal val stack: ItemStack,
    val description: String,
    val refresh: NativeRefresh,
    internal val drawing: Consumer<GuiGraphics>? = null,
) {
    companion object {
        private val nextId = AtomicLong()

        /** Automatically follows model/texture animation; use STATIC only for an intentional frozen image. */
        @JvmStatic
        @JvmOverloads
        fun snapshot(stack: ItemStack, refresh: NativeRefresh = NativeRefresh.AUTO): ItemIcon {
            RenderSystem.assertOnRenderThread()
            require(!stack.isEmpty) { "Item icons require a nonempty stack" }
            val copy = stack.copy()
            return ItemIcon(nextId.incrementAndGet(), copy, copy.hoverName.string, refresh)
        }

        /** An immutable resource drawing, prepared on the render thread in a 16x16 native GUI area. */
        @JvmStatic
        @JvmOverloads
        fun drawn(
            description: String,
            drawing: Consumer<GuiGraphics>,
            refresh: NativeRefresh = NativeRefresh.GAME_TICK,
        ): ItemIcon {
            RenderSystem.assertOnRenderThread()
            return ItemIcon(nextId.incrementAndGet(), ItemStack.EMPTY, description, refresh, drawing)
        }
    }
}

/**
 * Native icon rendering for one screen or HUD layer. Every displayed icon gets its image, however many there are.
 *
 * @param imageSize pixels drawn for an icon's 16 GUI units. By default each icon is drawn at the pixels it is laid out
 *   with, so it is sampled pixel for pixel like the game's own item rendering at any display size; an icon shown at two
 *   sizes is drawn at both. A fixed size draws every icon once at that size and resamples it on screen.
 * @param cacheCapacity icons kept while fewer are displayed: an icon that leaves the screen stays cached while it fits,
 *   so it returns without drawing again.
 * @param preparationsPerFrame icons drawn in one frame at most, which is also the size of one atlas page.
 */
data class NativeItemOptions(
    val imageSize: Int? = null,
    val cacheCapacity: Int = 128,
    val preparationsPerFrame: Int = 64,
) {
    companion object {
        internal val IMAGE_SIZES = 16..256
    }

    init {
        require(imageSize == null || imageSize in IMAGE_SIZES)
        require(cacheCapacity in 1..1024)
        require(preparationsPerFrame in 1..64)
    }
}

/** Native item pixels participate in Compose's layout, clipping, alpha and draw order. */
@Composable
fun MinecraftItemIcon(icon: ItemIcon, modifier: Modifier = Modifier) {
    val images = checkNotNull(LocalItemImages.current) { "MinecraftItemIcon requires a CompixelUI screen or HUD layer" }
    NativeImageContent(icon, icon.description, images, modifier.defaultMinSize(16.dp, 16.dp)) { measured ->
        val size = minOf(measured.width, measured.height).coerceIn(NativeItemOptions.IMAGE_SIZES)
        NativeImageAtlas.Size(size, size)
    }
}

internal val LocalItemImages = staticCompositionLocalOf<NativeImageMailbox<ItemIcon>?> { null }
