package dev.composemc.neoforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.NativeImageRegion
import dev.composemc.bridge.drawNativeImageRegion
import net.minecraft.world.item.ItemStack
import net.minecraft.client.gui.GuiGraphics
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicLong

/** An owned ItemStack copy. Create snapshots on the game thread, then pass the handle to Compose. */
class ItemIcon private constructor(
    internal val id: Long,
    internal val stack: ItemStack,
    val description: String,
    val refresh: IconRefresh,
    internal val drawing: Consumer<GuiGraphics>? = null,
) {
    companion object {
        private val nextId = AtomicLong()
        /** Automatically follows model/texture animation; use STATIC only for an intentional frozen image. */
        @JvmStatic @JvmOverloads
        fun snapshot(stack: ItemStack, refresh: IconRefresh = IconRefresh.AUTO): ItemIcon {
            RenderSystem.assertOnRenderThread()
            require(!stack.isEmpty) { "Item icons require a nonempty stack" }
            val copy = stack.copy()
            return ItemIcon(nextId.incrementAndGet(), copy, copy.hoverName.string, refresh)
        }

        /** An immutable resource drawing, prepared on the render thread in a 16x16 native GUI area. */
        @JvmStatic @JvmOverloads
        fun drawn(description: String, drawing: Consumer<GuiGraphics>, refresh: IconRefresh = IconRefresh.GAME_TICK): ItemIcon {
            RenderSystem.assertOnRenderThread()
            return ItemIcon(nextId.incrementAndGet(), ItemStack.EMPTY, description, refresh, drawing)
        }
    }
}

data class NativeItemOptions(
    val imageSize: Int = 64,
    val cacheCapacity: Int = 128,
    val preparationsPerFrame: Int = 8,
) {
    init {
        require(imageSize in 16..256)
        require(cacheCapacity in 1..1024)
        require(preparationsPerFrame in 1..64)
    }
}

/** Native pixels participate in Compose's transform, clip, alpha and draw order. */
@Composable
fun MinecraftItemIcon(icon: ItemIcon, modifier: Modifier = Modifier) {
    val images = checkNotNull(LocalItemImages.current) { "MinecraftItemIcon requires a NeoForgeComposeScreen" }
    val paint = remember { Paint() }
    DisposableEffect(images, icon) {
        images.retain(icon)
        onDispose { images.release(icon) }
    }
    Layout(content = {}, modifier = modifier.semantics { contentDescription = icon.description }.drawBehind {
        val region = images.request(icon)
        if (region != null) drawNativeImageRegion(region.image, region.source, paint)
        else drawRect(Color(0x443F4B50))
    }) { _, constraints ->
        layout(constraints.constrainWidth(16.dp.roundToPx()), constraints.constrainHeight(16.dp.roundToPx())) {}
    }
}

internal val LocalItemImages = staticCompositionLocalOf<ItemImageMailbox?> { null }

/** Only the EDT accesses this mailbox. It never reads the native ItemStack. */
internal class ItemImageMailbox(private val requestLimit: Int) {
    private data class Request(val icon: ItemIcon, var users: Int)
    // Atlas regions invalidate only the icons they replace.
    private val atlas = mutableStateMapOf<Long, NativeImageRegion>()
    private val requests = linkedMapOf<Long, Request>()
    fun retain(icon: ItemIcon) {
        val existing = requests[icon.id]
        if (existing != null) existing.users++
        else {
            check(requests.size < requestLimit) { "Too many active native icon variants; increase nativeItemOptions.cacheCapacity" }
            requests[icon.id] = Request(icon, 1)
        }
    }
    fun release(icon: ItemIcon) {
        val request = checkNotNull(requests[icon.id])
        if (--request.users == 0) requests.remove(icon.id)
    }
    fun request(icon: ItemIcon): NativeImageRegion? = atlas[icon.id]
    // Draw callbacks can be skipped when Compose replays a cached layer. Composition lifetime
    // preserves demand across those frames and includes the bounded Lazy layout prefetch window.
    fun activeRequests(): List<ItemIcon> = requests.values.map { it.icon }
    fun publishAtlas(regions: Map<Long, NativeImageRegion>) { atlas.putAll(regions) }
    fun removeAtlas(ids: Collection<Long>) { ids.forEach(atlas::remove) }
    fun clear() { atlas.clear() }
}
