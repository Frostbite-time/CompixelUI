package dev.compixel.forge.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.NativeIconAtlas
import dev.compixel.bridge.NativeImageRegion
import dev.compixel.bridge.drawNativeImageRegion
import java.util.TreeSet
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
    init {
        require(imageSize == null || imageSize in NativeIconAtlas.IMAGE_SIZES)
        require(cacheCapacity in 1..1024)
        require(preparationsPerFrame in 1..64)
    }
}

/**
 * Native pixels participate in Compose's transform, clip, alpha and draw order. The icon is drawn at the pixels it is
 * laid out with, so it matches the game's own item rendering at any size; a transform from a parent layer, such as a
 * graphicsLayer scale, resamples it.
 */
@Composable
fun MinecraftItemIcon(icon: ItemIcon, modifier: Modifier = Modifier) {
    val images = checkNotNull(LocalItemImages.current) { "MinecraftItemIcon requires a CompixelUI screen or HUD layer" }
    Layout(
        content = {},
        modifier = modifier.semantics { contentDescription = icon.description }.then(ItemImageElement(images, icon)),
    ) { _, constraints ->
        layout(constraints.constrainWidth(16.dp.roundToPx()), constraints.constrainHeight(16.dp.roundToPx())) {}
    }
}

internal val LocalItemImages = staticCompositionLocalOf<ItemImageMailbox?> { null }

private data class ItemImageElement(val images: ItemImageMailbox, val icon: ItemIcon) :
    ModifierNodeElement<ItemImageNode>() {
    override fun create() = ItemImageNode(images, icon)

    override fun update(node: ItemImageNode) = node.update(images, icon)
}

/**
 * Demands its icon at the pixels it is measured with while it is attached, and draws that image or, until it is drawn,
 * the nearest published one. Lazy layouts also measure the items they prefetch, so those are prepared before they
 * scroll in. EDT only.
 */
private class ItemImageNode(private var images: ItemImageMailbox, private var icon: ItemIcon) :
    Modifier.Node(), LayoutAwareModifierNode, DrawModifierNode {
    private class Demand(val images: ItemImageMailbox, val icon: ItemIcon, val imageSize: Int)

    private val paint = Paint()
    /** 0 until the first measurement; a node reused for another icon keeps it, as it may not be measured again. */
    private var imageSize = 0
    private var demand: Demand? = null

    // A new icon changes only the drawing, which update invalidates.
    override val shouldAutoInvalidate
        get() = false

    override fun onAttach() = demand()

    override fun onDetach() = release()

    override fun onRemeasured(size: IntSize) {
        imageSize = NativeIconAtlas.imageSize(minOf(size.width, size.height))
        demand()
    }

    fun update(images: ItemImageMailbox, icon: ItemIcon) {
        this.images = images
        this.icon = icon
        demand()
        invalidateDraw()
    }

    /** Retains the current demand before releasing the previous one, so a resized icon keeps its images. */
    private fun demand() {
        if (!isAttached || imageSize == 0) return
        val previous = demand
        if (previous?.images === images && previous.icon === icon && previous.imageSize == imageSize) return
        images.retain(icon, imageSize)
        release()
        demand = Demand(images, icon, imageSize)
    }

    private fun release() {
        demand?.let { it.images.release(it.icon, it.imageSize) }
        demand = null
    }

    override fun ContentDrawScope.draw() {
        val region = images.request(icon, imageSize)
        if (region != null) drawNativeImageRegion(region.image, region.source, paint) else drawRect(Color(0x443F4B50))
        drawContent()
    }
}

/** Only the EDT accesses this mailbox. It never reads the native ItemStack. */
internal class ItemImageMailbox {
    private class Demand(val icon: ItemIcon, var users: Int)

    // Drawing observes only an icon's revision. A region moved to a newer page image keeps its pixels, so drawing
    // recorded from the older image stays correct; changed pixels invalidate just the icons that show them.
    private val regions = HashMap<NativeIconAtlas.Variant, NativeImageRegion>()
    /** The published image sizes of each icon, to show the nearest one until the demanded size is drawn. */
    private val sizes = HashMap<Long, TreeSet<Int>>()
    private val revisions = HashMap<Long, MutableIntState>()
    private val demands = linkedMapOf<NativeIconAtlas.Variant, Demand>()
    /** Demands of each icon, over all its sizes. */
    private val users = HashMap<Long, Int>()

    fun retain(icon: ItemIcon, imageSize: Int) {
        val variant = NativeIconAtlas.Variant(icon.id, imageSize)
        val existing = demands[variant]
        if (existing != null) existing.users++ else demands[variant] = Demand(icon, 1)
        users.merge(icon.id, 1, Int::plus)
        revisions.getOrPut(icon.id) { mutableIntStateOf(0) }
    }

    fun release(icon: ItemIcon, imageSize: Int) {
        val variant = NativeIconAtlas.Variant(icon.id, imageSize)
        val demand = checkNotNull(demands[variant])
        if (--demand.users == 0) demands.remove(variant)
        val remaining = checkNotNull(users[icon.id]) - 1
        if (remaining > 0) users[icon.id] = remaining
        else {
            users.remove(icon.id)
            if (icon.id !in sizes) revisions.remove(icon.id)
        }
    }

    /** The region drawn at [imageSize], or until that is drawn the icon's nearest published size. */
    fun request(icon: ItemIcon, imageSize: Int): NativeImageRegion? {
        revisions.getOrPut(icon.id) { mutableIntStateOf(0) }.intValue
        regions[NativeIconAtlas.Variant(icon.id, imageSize)]?.let {
            return it
        }
        val published = sizes[icon.id] ?: return null
        val above = published.ceiling(imageSize)
        val below = published.floor(imageSize)
        val nearest = if (above == null || below != null && imageSize - below < above - imageSize) below else above
        return nearest?.let { regions[NativeIconAtlas.Variant(icon.id, it)] }
    }

    // Draw callbacks can be skipped when Compose replays a cached layer. Attached, measured icons keep
    // their demand across those frames, including the bounded Lazy layout prefetch window.
    fun activeRequests(): List<NativeIconAtlas.Request<ItemIcon>> = demands.map { (variant, demand) ->
        NativeIconAtlas.Request(demand.icon, variant.imageSize)
    }

    fun publishAtlas(regions: Map<NativeIconAtlas.Variant, NativeImageRegion>, changed: Set<NativeIconAtlas.Variant>) {
        this.regions.putAll(regions)
        regions.keys.forEach { sizes.getOrPut(it.id) { TreeSet() } += it.imageSize }
        changed.forEach { revisions[it.id]?.let { revision -> revision.intValue++ } }
    }

    fun removeAtlas(variants: Collection<NativeIconAtlas.Variant>) {
        variants.forEach { variant ->
            regions.remove(variant)
            sizes[variant.id]?.let { if (it.remove(variant.imageSize) && it.isEmpty()) sizes.remove(variant.id) }
            if (variant.id in users) revisions[variant.id]?.let { it.intValue++ }
            else if (variant.id !in sizes) revisions.remove(variant.id)
        }
    }

    fun clear() {
        regions.clear()
        sizes.clear()
        revisions.values.forEach { it.intValue++ }
        revisions.keys.retainAll(users.keys)
    }
}
