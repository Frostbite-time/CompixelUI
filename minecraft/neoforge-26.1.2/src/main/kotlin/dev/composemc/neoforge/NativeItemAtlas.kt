package dev.composemc.neoforge

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Batches native icons into small GPU atlas pages. Slots remain stable while their icons are
 * cached. A page redraw prepares at most [NativeItemOptions.preparationsPerFrame] icons, then one
 * immutable GPU snapshot replaces that page's image. Vulkan publishes the completed page on the
 * next frame; OpenGL can publish immediately.
 */
internal class NativeItemAtlas(
    private val mailbox: ItemImageMailbox,
    private val options: NativeItemOptions,
    private val snapshots: NativeSnapshots,
) : NativeItemPreparer {
    private class Entry(val icon: ItemIcon, val slot: Int, val refresh: IconRefresh) {
        var drawnFrame = 0L
        var drawnAt = 0L
        var tick = 0L
        var guiScale = 0
        var hasImage = false
    }
    private class Page {
        var image: Image? = null
        var published = emptySet<Long>()
    }
    private class Pending(val buffer: Int, val page: Int, val entries: List<Entry>)

    private val pageCapacity = minOf(options.cacheCapacity, options.preparationsPerFrame)
    private val columns = ceil(sqrt(pageCapacity.toDouble())).toInt()
    private val rows = (pageCapacity + columns - 1) / columns
    private val scale = options.imageSize / 16f
    private val atlasWidth = ceil(columns * SLOT_UNITS * scale).toInt()
    private val atlasHeight = ceil(rows * SLOT_UNITS * scale).toInt()
    private val pages = Array((options.cacheCapacity + pageCapacity - 1) / pageCapacity) { Page() }
    private val slots = arrayOfNulls<Entry>(options.cacheCapacity)
    private val byId = HashMap<Long, Entry>()
    private var capture: NativeGuiCapture? = null
    private var visible = emptyList<ItemIcon>()
    private var pending: Pending? = null
    private var buffer = -1
    private var frame = 0L
    private var generation = 0L
    private var prepared = 0L
    private var retired = 0L
    private var animationRefreshes = 0L
    private var closed = false

    override val statistics get() = NativeItemStatistics(
        activeVariants = visible.size,
        cachedImages = visible.count { byId[it.id]?.hasImage == true },
        pendingImages = visible.count { byId[it.id]?.hasImage != true },
        preparedImages = prepared,
        retiredImages = retired,
        lastRequestGeneration = generation,
        dynamicVariants = visible.count {
            byId[it.id]?.let { entry -> entry.hasImage && entry.refresh.kind != IconRefresh.Kind.STATIC } == true
        },
        animationRefreshes = animationRefreshes,
    )

    override fun recorded(frameGeneration: Long) {
        visible = ComposeThread.call { mailbox.activeRequests() }
        generation = frameGeneration
    }

    override fun prepare(now: Long): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        frame++
        var changed = false
        pending?.let { publish(it); pending = null; changed = true }
        val visibleIds = visible.mapTo(hashSetOf()) { it.id }
        for (icon in visible) {
            if (byId.containsKey(icon.id)) continue
            val free = slots.indexOfFirst { it == null }
            val index = if (free >= 0) free else slots.indexOfFirst { it!!.icon.id !in visibleIds }
            if (index < 0) break // All cached slots are pinned by visible icons.
            slots[index]?.let { evict(it); changed = true }
            val entry = Entry(icon, index, icon.resolvedRefresh())
            slots[index] = entry
            byId[icon.id] = entry
        }
        val guiScale = Minecraft.getInstance().window.guiScale
        val tick = NativeIconClock.tick()
        val duePage = pages.indices.filter { page ->
            pageEntries(page).any { it.icon.id in visibleIds && due(it, now, tick, guiScale) }
        }.minByOrNull { page ->
            pageEntries(page).filter { it.icon.id in visibleIds && due(it, now, tick, guiScale) }
                .minOf { it.drawnFrame }
        }
        if (duePage == null) return changed

        val entries = pageEntries(duePage)
        if (entries.isEmpty()) return changed
        buffer = (buffer + 1) % if (snapshots.immediate) 1 else 2
        draw(buffer, entries)
        entries.forEach { entry ->
            if (entry.hasImage && entry.refresh.kind != IconRefresh.Kind.STATIC) animationRefreshes++
            entry.drawnFrame = frame
            entry.drawnAt = now
            entry.tick = tick
            entry.guiScale = guiScale
        }
        val next = Pending(buffer, duePage, entries)
        if (snapshots.immediate) { publish(next); return true }
        pending = next
        return changed
    }

    private fun due(entry: Entry, now: Long, tick: Long, guiScale: Int): Boolean {
        if (!entry.hasImage || entry.guiScale != guiScale) return true
        return when (entry.refresh.kind) {
            IconRefresh.Kind.STATIC -> false
            IconRefresh.Kind.GAME_TICK -> entry.tick != tick
            IconRefresh.Kind.FRAME -> entry.drawnFrame != frame
            IconRefresh.Kind.INTERVAL -> now - entry.drawnAt >= entry.refresh.millis * 1_000_000L
            IconRefresh.Kind.AUTO -> error("Unresolved icon refresh policy")
        }
    }

    private fun pageEntries(page: Int): List<Entry> = (page * pageCapacity until minOf((page + 1) * pageCapacity, slots.size))
        .mapNotNull { slots[it] }

    private fun draw(buffer: Int, entries: List<Entry>) {
        val target = capture ?: NativeGuiCapture(atlasWidth, atlasHeight, if (snapshots.immediate) 1 else 2)
            .also { capture = it }
        val font = Minecraft.getInstance().font
        target.render(buffer, columns * SLOT_UNITS, rows * SLOT_UNITS) { graphics ->
            entries.forEach { entry ->
                val position = entry.slot % pageCapacity
                val x = position % columns * SLOT_UNITS
                val y = position / columns * SLOT_UNITS
                val drawing = entry.icon.drawing
                if (drawing == null) {
                    graphics.fakeItem(entry.icon.stack, x, y)
                    graphics.itemDecorations(font, entry.icon.stack, x, y)
                } else {
                    graphics.pose().pushMatrix()
                    try {
                        graphics.pose().translate(x.toFloat(), y.toFloat())
                        drawing.accept(graphics)
                    } finally { graphics.pose().popMatrix() }
                }
            }
            true
        }
    }

    private fun publish(result: Pending) {
        val copy = snapshots.snapshot(checkNotNull(capture).texture(result.buffer), atlasWidth, atlasHeight)
        prepared++
        val page = pages[result.page]
        val regions = HashMap<Long, NativeImageRegion>(result.entries.size * 2)
        result.entries.forEach { entry ->
            if (slots[entry.slot] !== entry) return@forEach
            val position = entry.slot % pageCapacity
            regions[entry.icon.id] = NativeImageRegion(copy, Rect.makeXYWH(
                position % columns * SLOT_UNITS * scale, position / columns * SLOT_UNITS * scale,
                options.imageSize.toFloat(), options.imageSize.toFloat()))
            entry.hasImage = true
        }
        ComposeThread.call {
            mailbox.removeAtlas(page.published - regions.keys)
            mailbox.publishAtlas(regions)
        }
        page.published = regions.keys
        page.image?.let { snapshots.release(it); retired++ }
        page.image = copy
    }

    private fun evict(entry: Entry) {
        byId.remove(entry.icon.id)
        slots[entry.slot] = null
        val page = pages[entry.slot / pageCapacity]
        if (entry.icon.id in page.published) {
            ComposeThread.call { mailbox.removeAtlas(listOf(entry.icon.id)) }
            page.published = page.published - entry.icon.id
        }
    }

    override fun reset() {
        RenderSystem.assertOnRenderThread()
        pending = null
        slots.fill(null)
        byId.clear()
        capture?.close()
        capture = null
        buffer = -1
        ComposeThread.call { mailbox.clear() }
        pages.forEach { page ->
            page.image?.let { snapshots.release(it); retired++ }
            page.image = null
            page.published = emptySet()
        }
    }

    override fun close() {
        if (closed) return
        reset()
        visible = emptyList()
        closed = true
    }

    private companion object {
        /** 16 GUI units per icon plus a gutter for stack counts and shadows. */
        const val SLOT_UNITS = 18
    }
}
