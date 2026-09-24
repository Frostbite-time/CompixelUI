package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.world.item.ItemDisplayContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentLinkedQueue

data class NativeItemStatistics(
    val activeVariants: Int = 0,
    val cachedImages: Int = 0,
    val pendingImages: Int = 0,
    val preparedImages: Long = 0,
    val retiredImages: Long = 0,
    val lastRequestGeneration: Long = 0,
    val dynamicVariants: Int = 0,
    val animationRefreshes: Long = 0,
)

/** Prepares native icon pixels on the render thread and publishes immutable images to Compose. */
internal interface NativeItemPreparer : AutoCloseable {
    val statistics: NativeItemStatistics
    fun recorded(frameGeneration: Long)
    fun prepare(now: Long): Boolean
    fun reset()
}

/** Reads native pixels back per icon; used where the renderer cannot copy them on the GPU. */
internal class NativeItemRenderer(
    private val mailbox: ItemImageMailbox,
    private val options: NativeItemOptions,
) : NativeItemPreparer {
    private data class Entry(val image: Image, val updated: Long, val refresh: IconRefresh, val stamp: Long)
    private data class Completion(val id: Long, val epoch: Long, val issuedAt: Long,
                                  val refresh: IconRefresh, val stamp: Long, val pixels: ByteArray)

    private val completions = ConcurrentLinkedQueue<Completion>()
    private val inFlight = mutableSetOf<Long>()
    private val retiring = ArrayList<Image>()
    private val updates = linkedMapOf<Long, Image>()
    private val removals = ArrayList<Pair<Long, Image>>()
    private val cache = VisibleIconCache<Long, Entry>(options.cacheCapacity) { id, entry ->
        removals += id to entry.image
        retiring += entry.image
        retired++
    }
    private var capture: NativeGuiCapture? = null
    private var visible = emptyList<ItemIcon>()
    private var generation = 0L
    private var epoch = 0L
    private var frame = 0L
    private var prepared = 0L
    private var retired = 0L
    private var animationRefreshes = 0L
    private var closed = false

    override val statistics get() = NativeItemStatistics(
        activeVariants = visible.size,
        cachedImages = cache.size,
        pendingImages = inFlight.size,
        preparedImages = prepared,
        retiredImages = retired,
        lastRequestGeneration = generation,
        dynamicVariants = visible.count { cache[it.id]?.refresh?.kind?.let { kind -> kind != IconRefresh.Kind.STATIC } == true },
        animationRefreshes = animationRefreshes,
    )

    override fun recorded(frameGeneration: Long) {
        visible = ComposeThread.call { mailbox.activeRequests() }
        cache.visible = visible.mapTo(hashSetOf()) { it.id }
        generation = frameGeneration
    }

    /** Readbacks complete asynchronously; each host frame starts at most one bounded batch. */
    override fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        frame++
        return try {
            val changed = collectCompleted()
            // Missing images first, then the least recently updated: continuously refreshing
            // icons must not starve the rest of the visible set.
            val due = visible.filter { icon ->
                icon.id !in inFlight && cache[icon.id].let { it == null || needsRefresh(it, now) }
            }.sortedBy { cache[it.id]?.updated ?: Long.MIN_VALUE }
            var budget = options.preparationsPerFrame
            for (icon in due) {
                if (budget == 0 || inFlight.size >= options.cacheCapacity) break
                if (!cache.canStore(icon.id)) continue
                val refresh = cache[icon.id]?.refresh ?: icon.resolvedRefresh()
                val stamp = if (refresh.kind == IconRefresh.Kind.FRAME) frame else NativeIconClock.tick()
                val capturedEpoch = epoch
                capture().capture(icon) { pixels ->
                    completions.add(Completion(icon.id, capturedEpoch, now, refresh, stamp, pixels))
                }
                inFlight += icon.id
                budget--
            }
            changed
        } finally {
            try { publishImages() } finally { retireImages() }
        }
    }

    private fun capture(): NativeGuiCapture = capture ?: NativeGuiCapture(options.imageSize).also { capture = it }

    private fun needsRefresh(entry: Entry, now: Long): Boolean = when (entry.refresh.kind) {
        IconRefresh.Kind.STATIC -> false
        IconRefresh.Kind.GAME_TICK -> entry.stamp != NativeIconClock.tick()
        IconRefresh.Kind.FRAME -> entry.stamp != frame
        IconRefresh.Kind.INTERVAL -> now - entry.updated >= entry.refresh.millis * 1_000_000L
        IconRefresh.Kind.AUTO -> error("Unresolved icon refresh policy")
    }

    private fun collectCompleted(): Boolean {
        var changed = false
        while (true) {
            val completion = completions.poll() ?: break
            if (completion.epoch != epoch) continue
            inFlight.remove(completion.id)
            if (completion.id !in cache.visible || !cache.canStore(completion.id)) continue
            val image = Image.makeRaster(ImageInfo(options.imageSize, options.imageSize,
                ColorType.RGBA_8888, ColorAlphaType.PREMUL), completion.pixels, options.imageSize * 4)
            val previous = cache[completion.id]
            cache.put(completion.id, Entry(image, completion.issuedAt, completion.refresh, completion.stamp))
            updates[completion.id] = image
            if (previous != null) animationRefreshes++
            prepared++
            changed = true
        }
        return changed
    }

    private fun publishImages() {
        if (updates.isEmpty() && removals.isEmpty()) return
        try { ComposeThread.call {
            removals.forEach { (id, image) -> mailbox.remove(id, image) }
            updates.forEach { (id, image) -> mailbox.put(id, image) }
        } } finally { updates.clear(); removals.clear() }
    }

    private fun retireImages() {
        if (retiring.isEmpty()) return
        val old = retiring.toList()
        retiring.clear()
        FrameRetirement.afterFrame { old.forEach(Image::close) }
    }

    override fun reset() {
        RenderSystem.assertOnRenderThread()
        epoch++
        inFlight.clear()
        completions.clear()
        capture?.close()
        capture = null
        try {
            cache.close()
            updates.clear()
            removals.clear()
            ComposeThread.call { mailbox.clear() }
        } finally { retireImages() }
    }

    override fun close() {
        if (closed) return
        reset()
        visible = emptyList()
        cache.visible = emptySet()
        closed = true
    }
}

/** Resolves AUTO from the item model: animated sprites, overrides and glint refresh every frame. */
internal fun ItemIcon.resolvedRefresh(): IconRefresh {
    if (refresh !== IconRefresh.AUTO) return refresh
    if (stack.isEmpty) return IconRefresh.GAME_TICK
    val minecraft = Minecraft.getInstance()
    val state = TrackingItemStackRenderState()
    minecraft.itemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, minecraft.level, null, 0)
    return if (state.isAnimated || stack.hasFoil()) IconRefresh.FRAME else IconRefresh.STATIC
}
