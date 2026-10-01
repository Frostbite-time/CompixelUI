package dev.compixel.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.math.abs

/** Compose-side demands and published regions. The id function may read only immutable handle metadata. */
class NativeImageMailbox<I : Any>(private val id: (I) -> Long) {
    private class Demand<I>(val content: I, var users: Int)

    private val regions = HashMap<NativeImageAtlas.Variant, NativeImageRegion>()
    private val sizes = HashMap<Long, MutableSet<NativeImageAtlas.Size>>()
    private val revisions = HashMap<Long, MutableIntState>()
    private val demands = linkedMapOf<NativeImageAtlas.Variant, Demand<I>>()
    private val users = HashMap<Long, Int>()

    fun retain(content: I, size: NativeImageAtlas.Size) {
        val key = id(content)
        val variant = NativeImageAtlas.Variant(key, size)
        val demand = demands[variant]
        if (demand == null) demands[variant] = Demand(content, 1) else demand.users++
        users.merge(key, 1, Int::plus)
        revisions.getOrPut(key) { mutableIntStateOf(0) }
    }

    fun release(content: I, size: NativeImageAtlas.Size) {
        val key = id(content)
        val variant = NativeImageAtlas.Variant(key, size)
        val demand = checkNotNull(demands[variant])
        if (--demand.users == 0) demands.remove(variant)
        val remaining = checkNotNull(users[key]) - 1
        if (remaining > 0) users[key] = remaining
        else {
            users.remove(key)
            if (key !in sizes) revisions.remove(key)
        }
    }

    fun request(content: I, size: NativeImageAtlas.Size): NativeImageRegion? {
        val key = id(content)
        revisions.getOrPut(key) { mutableIntStateOf(0) }.intValue
        regions[NativeImageAtlas.Variant(key, size)]?.let {
            return it
        }
        val nearest =
            sizes[key]?.minWithOrNull(
                compareBy<NativeImageAtlas.Size> {
                        abs(it.width.toLong() - size.width) + abs(it.height.toLong() - size.height)
                    }
                    .thenBy { it.width }
                    .thenBy { it.height }
            ) ?: return null
        return regions[NativeImageAtlas.Variant(key, nearest)]
    }

    fun activeRequests(): List<NativeImageAtlas.Request<I>> = demands.map { (variant, demand) ->
        NativeImageAtlas.Request(demand.content, variant.size)
    }

    fun publish(
        regions: Map<NativeImageAtlas.Variant, NativeImageRegion>,
        changed: Set<NativeImageAtlas.Variant>,
        removed: Set<NativeImageAtlas.Variant>,
    ) {
        removed.forEach { variant ->
            this.regions.remove(variant)
            sizes[variant.id]?.let { if (it.remove(variant.size) && it.isEmpty()) sizes.remove(variant.id) }
            if (variant.id in users) revisions[variant.id]?.let { it.intValue++ }
            else if (variant.id !in sizes) revisions.remove(variant.id)
        }
        this.regions.putAll(regions)
        regions.keys.forEach { sizes.getOrPut(it.id) { linkedSetOf() } += it.size }
        changed.forEach { revisions[it.id]?.let { revision -> revision.intValue++ } }
    }

    fun clear() {
        regions.clear()
        sizes.clear()
        revisions.values.forEach { it.intValue++ }
        revisions.keys.retainAll(users.keys)
    }
}

/** A native image participates in normal Compose layout, clipping, alpha and drawing order. */
@Composable
fun <I : Any> NativeImageContent(
    content: I,
    description: String,
    mailbox: NativeImageMailbox<I>,
    modifier: Modifier,
    imageSize: (IntSize) -> NativeImageAtlas.Size,
) {
    Layout(
        content = {},
        modifier =
            modifier
                .semantics { contentDescription = description }
                .then(NativeImageElement(mailbox, content, imageSize)),
    ) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}

private data class NativeImageElement<I : Any>(
    val mailbox: NativeImageMailbox<I>,
    val content: I,
    val imageSize: (IntSize) -> NativeImageAtlas.Size,
) : ModifierNodeElement<NativeImageNode<I>>() {
    override fun create() = NativeImageNode(mailbox, content, imageSize)

    override fun update(node: NativeImageNode<I>) = node.update(mailbox, content, imageSize)
}

private class NativeImageNode<I : Any>(
    private var mailbox: NativeImageMailbox<I>,
    private var content: I,
    private var imageSize: (IntSize) -> NativeImageAtlas.Size,
) : Modifier.Node(), LayoutAwareModifierNode, DrawModifierNode {
    private class Demand<I : Any>(val mailbox: NativeImageMailbox<I>, val content: I, val size: NativeImageAtlas.Size)

    private val paint = Paint()
    private var measured: IntSize? = null
    private var demand: Demand<I>? = null
    override val shouldAutoInvalidate
        get() = false

    override fun onAttach() = demand()

    override fun onDetach() = release()

    override fun onRemeasured(size: IntSize) {
        measured = size
        demand()
    }

    fun update(mailbox: NativeImageMailbox<I>, content: I, imageSize: (IntSize) -> NativeImageAtlas.Size) {
        this.mailbox = mailbox
        this.content = content
        this.imageSize = imageSize
        demand()
        invalidateDraw()
    }

    private fun demand() {
        val measured = measured ?: return
        if (!isAttached) return
        if (measured.width <= 0 || measured.height <= 0) {
            release()
            return
        }
        val size = imageSize(measured)
        val previous = demand
        if (previous?.mailbox === mailbox && previous.content === content && previous.size == size) return
        mailbox.retain(content, size)
        release()
        demand = Demand(mailbox, content, size)
    }

    private fun release() {
        demand?.let { it.mailbox.release(it.content, it.size) }
        demand = null
    }

    override fun ContentDrawScope.draw() {
        val region = demand?.let { it.mailbox.request(it.content, it.size) }
        if (region != null) drawNativeImageRegion(region.image, region.source, paint) else drawRect(Color(0x443F4B50))
        drawContent()
    }
}
