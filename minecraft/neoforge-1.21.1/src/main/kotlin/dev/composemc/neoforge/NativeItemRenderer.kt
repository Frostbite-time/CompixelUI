package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Image

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

/** Native model access, preparation and image retirement stay on the game/render thread. */
internal class NativeItemRenderer(
    private val backend: ScreenFrameRenderer,
    private val mailbox: ItemImageMailbox,
    private val options: NativeItemOptions,
) : AutoCloseable {
    private data class Entry(val image: Image, val updated: Long, val refresh: IconRefresh, val stamp: Long)
    private val animations = NativeIconAnimation()
    private var frame = 0L
    private var animationRefreshes = 0L
    private var visible = emptyList<ItemIcon>()
    private var prepared = 0L
    private var retired = 0L
    private var generation = 0L
    private var pending = 0
    private val retiring = ArrayList<Image>()
    private val updates = linkedMapOf<Long, Image>()
    private val removals = ArrayList<Pair<Long, Image>>()
    private val target = NativeGuiRenderTarget(backend)
    private val cache = VisibleIconCache<Long, Entry>(options.cacheCapacity) { id, entry ->
        removals += id to entry.image
        retiring += entry.image
        retired++
    }
    val statistics get() = NativeItemStatistics(visible.size, cache.size, pending, prepared, retired, generation,
        visible.count { cache[it.id]?.refresh?.kind?.let { kind -> kind != IconRefresh.Kind.STATIC } == true }, animationRefreshes)

    fun recorded(frameGeneration: Long) {
        visible = ComposeThread.call { mailbox.activeRequests() }
        cache.visible = visible.mapTo(hashSetOf()) { it.id }
        generation = frameGeneration
    }

    /** At most one bounded preparation batch is allowed per host frame. */
    fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        frame++
        return try { prepareBatch(now) } finally {
            try { publishImages() } finally { retireImages() }
        }
    }

    private fun prepareBatch(now: Long): Boolean {
        var changed = false
        var budget = options.preparationsPerFrame
        val needed = visible.filter { icon ->
            val entry = cache[icon.id]
            entry == null || when (entry.refresh.kind) {
                IconRefresh.Kind.STATIC -> false
                IconRefresh.Kind.GAME_TICK -> entry.stamp != NativeIconClock.tick()
                IconRefresh.Kind.FRAME -> entry.stamp != frame
                IconRefresh.Kind.INTERVAL -> now - entry.updated >= entry.refresh.millis * 1_000_000L
                IconRefresh.Kind.AUTO -> error("Unresolved icon refresh policy")
            }
        }.sortedBy { cache[it.id]?.updated ?: Long.MIN_VALUE }
        pending = needed.size
        for (icon in needed) {
            if (budget == 0) break
            if (!cache.canStore(icon.id)) continue
            val previous = cache[icon.id]
            val refresh = previous?.refresh ?: animations.resolve(icon)
            val image = renderIcon(icon)
            val stamp = if (refresh.kind == IconRefresh.Kind.FRAME) frame else NativeIconClock.tick()
            cache.put(icon.id, Entry(image, now, refresh, stamp))
            updates[icon.id] = image
            if (previous != null) animationRefreshes++
            prepared++
            pending--
            budget--
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

    fun reset() {
        RenderSystem.assertOnRenderThread()
        animations.clear()
        try {
            cache.close(); updates.clear(); removals.clear()
            ComposeThread.call { mailbox.clear() }
        } finally { retireImages() }
        // Keep the last visible handles so a reload can repopulate them immediately.
        pending = visible.size
    }

    private fun retireImages() {
        if (retiring.isEmpty()) return
        try { backend.releaseNativeImages(retiring) } finally { retiring.clear() }
    }

    private fun renderIcon(icon: ItemIcon): Image {
        val (source, _) = target.draw(options.imageSize, options.imageSize, 16f, 16f) { graphics ->
            if (icon.drawing != null) icon.drawing.accept(graphics)
            else {
                graphics.renderFakeItem(icon.stack, 0, 0)
                graphics.renderItemDecorations(Minecraft.getInstance().font, icon.stack, 0, 0)
            }
        }
        return backend.copyNativeImage(source)
    }

    override fun close() {
        reset()
        visible = emptyList()
        cache.visible = emptySet()
        pending = 0
        target.close()
    }
}
