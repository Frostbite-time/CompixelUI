package dev.compixel.forge.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.bridge.drawNativeImageRegion
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
    val refresh: IconRefresh,
    internal val drawing: Consumer<GuiGraphics>? = null,
) {
    companion object {
        private val nextId = AtomicLong()

        /** Automatically follows model/texture animation; use STATIC only for an intentional frozen image. */
        @JvmStatic
        @JvmOverloads
        fun snapshot(stack: ItemStack, refresh: IconRefresh = IconRefresh.AUTO): ItemIcon {
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
            refresh: IconRefresh = IconRefresh.GAME_TICK,
        ): ItemIcon {
            RenderSystem.assertOnRenderThread()
            return ItemIcon(nextId.incrementAndGet(), ItemStack.EMPTY, description, refresh, drawing)
        }
    }
}

/**
 * Native icon rendering for one screen or HUD layer. Every displayed icon gets its image, however many there are.
 *
 * @param imageSize pixels drawn for an icon's 16 GUI units.
 * @param cacheCapacity icons kept while fewer are displayed: an icon that leaves the screen stays cached while it fits,
 *   so it returns without drawing again.
 * @param preparationsPerFrame icons drawn in one frame at most, which is also the size of one atlas page.
 */
data class NativeItemOptions(
    val imageSize: Int = 64,
    val cacheCapacity: Int = 128,
    val preparationsPerFrame: Int = 64,
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
    val images = checkNotNull(LocalItemImages.current) { "MinecraftItemIcon requires a CompixelUI screen or HUD layer" }
    val paint = remember { Paint() }
    DisposableEffect(images, icon) {
        images.retain(icon)
        onDispose { images.release(icon) }
    }
    Layout(
        content = {},
        modifier =
            modifier
                .semantics { contentDescription = icon.description }
                .drawBehind {
                    val region = images.request(icon)
                    if (region != null) drawNativeImageRegion(region.image, region.source, paint)
                    else drawRect(Color(0x443F4B50))
                },
    ) { _, constraints ->
        layout(constraints.constrainWidth(16.dp.roundToPx()), constraints.constrainHeight(16.dp.roundToPx())) {}
    }
}

internal val LocalItemImages = staticCompositionLocalOf<ItemImageMailbox?> { null }

/** Only the EDT accesses this mailbox. It never reads the native ItemStack. */
internal class ItemImageMailbox {
    private data class Request(val icon: ItemIcon, var users: Int)

    // Drawing observes only an icon's revision. A region moved to a newer page image keeps its pixels, so drawing
    // recorded from the older image stays correct; changed pixels invalidate just the icons that show them.
    private val regions = HashMap<Long, NativeImageRegion>()
    private val revisions = HashMap<Long, MutableIntState>()
    private val requests = linkedMapOf<Long, Request>()

    fun retain(icon: ItemIcon) {
        val existing = requests[icon.id]
        if (existing != null) existing.users++ else requests[icon.id] = Request(icon, 1)
        revisions.getOrPut(icon.id) { mutableIntStateOf(0) }
    }

    fun release(icon: ItemIcon) {
        val request = checkNotNull(requests[icon.id])
        if (--request.users > 0) return
        requests.remove(icon.id)
        if (icon.id !in regions) revisions.remove(icon.id)
    }

    fun request(icon: ItemIcon): NativeImageRegion? {
        revisions.getOrPut(icon.id) { mutableIntStateOf(0) }.intValue
        return regions[icon.id]
    }

    // Draw callbacks can be skipped when Compose replays a cached layer. Composition lifetime
    // preserves demand across those frames and includes the bounded Lazy layout prefetch window.
    fun activeRequests(): List<ItemIcon> = requests.values.map { it.icon }

    fun publishAtlas(regions: Map<Long, NativeImageRegion>, changed: Set<Long>) {
        this.regions.putAll(regions)
        changed.forEach { revisions[it]?.let { revision -> revision.intValue++ } }
    }

    fun removeAtlas(ids: Collection<Long>) {
        ids.forEach { id ->
            regions.remove(id)
            if (id in requests) revisions[id]?.let { it.intValue++ } else revisions.remove(id)
        }
    }

    fun clear() {
        regions.clear()
        revisions.values.forEach { it.intValue++ }
        revisions.keys.retainAll(requests.keys)
    }
}
