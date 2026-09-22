package dev.composemc.neoforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.world.item.ItemStack
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.geometry.Rect

/** An owned ItemStack copy. Create snapshots on the game thread, then pass the handle to Compose. */
class ItemIcon private constructor(
    internal val id: Long,
    internal val stack: ItemStack,
    val description: String,
    val refresh: IconRefresh,
    internal val drawing: Consumer<GuiGraphicsExtractor>? = null,
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
        fun drawn(description: String, drawing: Consumer<GuiGraphicsExtractor>, refresh: IconRefresh = IconRefresh.GAME_TICK): ItemIcon {
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

/** Extracts a Minecraft item at its Compose position with the visible layout clip. */
@Composable
fun MinecraftItemIcon(icon: ItemIcon, modifier: Modifier = Modifier) {
    val images = checkNotNull(LocalItemImages.current) { "MinecraftItemIcon requires a NeoForgeComposeScreen" }
    DisposableEffect(images, icon) {
        images.retain(icon)
        onDispose { images.release(icon) }
    }
    Layout(content = {}, modifier = modifier
        .semantics { contentDescription = icon.description }
        .onGloballyPositioned { images.position(icon, Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())), it.boundsInRoot()) }
        // 26.2 extracts native item commands after the retained Compose frame.
        .drawBehind {}) { _, constraints ->
        layout(constraints.constrainWidth(16.dp.roundToPx()), constraints.constrainHeight(16.dp.roundToPx())) {}
    }
}

internal val LocalItemImages = staticCompositionLocalOf<ItemImageMailbox?> { null }
internal data class PositionedItemIcon(val icon: ItemIcon, val bounds: Rect, val clip: Rect)

/** Only the EDT accesses this mailbox. It never reads the native ItemStack. */
internal class ItemImageMailbox(private val requestLimit: Int) {
    private data class Request(val icon: ItemIcon, var users: Int)
    private val images = mutableStateMapOf<Long, org.jetbrains.skia.Image>()
    private val requests = linkedMapOf<Long, Request>()
    private val positions = mutableMapOf<Long, Pair<Rect, Rect>>()
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
        if (--request.users == 0) {
            requests.remove(icon.id)
            positions.remove(icon.id)
        }
    }
    fun request(icon: ItemIcon): org.jetbrains.skia.Image? {
        return images[icon.id]
    }
    // Draw callbacks can be skipped when Compose replays a cached layer. Composition lifetime
    // preserves demand across those frames and includes the bounded Lazy layout prefetch window.
    fun activeRequests(): List<ItemIcon> = requests.values.map { it.icon }
    fun position(icon: ItemIcon, bounds: Rect, clip: Rect) { if (requests.containsKey(icon.id)) positions[icon.id] = bounds to clip }
    fun positionedRequests(): List<PositionedItemIcon> = requests.values.mapNotNull { request ->
        positions[request.icon.id]?.let { PositionedItemIcon(request.icon, it.first, it.second) }
    }
    fun put(id: Long, image: org.jetbrains.skia.Image) { images[id] = image }
    fun remove(id: Long, image: org.jetbrains.skia.Image) { if (images[id] === image) images.remove(id) }
    fun clear() { images.clear() }
}
