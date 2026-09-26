package dev.composemc.forge.item

import com.mojang.blaze3d.systems.RenderSystem
import dev.composemc.bridge.ComposeThread
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil
import net.minecraft.core.component.DataComponents
import net.neoforged.neoforge.client.ClientHooks
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import dev.composemc.forge.render.FrameRetirement
import dev.composemc.forge.render.NativeSnapshots
import dev.composemc.forge.render.ScreenMetrics

data class NativeTooltipStatistics(
    val visible: Boolean = false,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val components: Int = 0,
    val richComponents: Int = 0,
    val preparedImages: Long = 0,
    val retiredImages: Long = 0,
    val lastRequestGeneration: Long = 0,
)

/** Extracts native components into an image that Compose owns and draws. */
internal class NativeTooltipRenderer(
    private val mailbox: ItemTooltipMailbox,
    private val snapshots: NativeSnapshots?,
) : AutoCloseable {
    private data class Layout(val width: Int, val height: Int, val components: Int, val richComponents: Int)
    private data class Completion(val epoch: Long, val request: ItemTooltipRequest,
                                  val metrics: ScreenMetrics, val layout: Layout, val pixels: ByteArray)
    private data class Pending(val capture: NativeGuiCapture, val epoch: Long, val request: ItemTooltipRequest,
                               val metrics: ScreenMetrics, val layout: Layout)

    private val completions = ConcurrentLinkedQueue<Completion>()
    private var capture: NativeGuiCapture? = null
    private var captureMetrics: ScreenMetrics? = null
    private var image: Image? = null
    private var imageRequest: ItemTooltipRequest? = null
    private var layout: Layout? = null
    private var request: ItemTooltipRequest? = null
    private var attemptedRequest: ItemTooltipRequest? = null
    private var attemptedMetrics: ScreenMetrics? = null
    private var attemptedAt = Long.MIN_VALUE
    private var inFlight = false
    private var pending: Pending? = null
    private var epoch = 0L
    private var generation = 0L
    private var prepared = 0L
    private var retired = 0L
    private var closed = false

    val statistics get() = NativeTooltipStatistics(image != null && imageRequest === request,
        layout?.width ?: 0, layout?.height ?: 0, layout?.components ?: 0, layout?.richComponents ?: 0,
        prepared, retired, generation)

    fun recorded(frameGeneration: Long) {
        request = ComposeThread.call { mailbox.request }
        generation = frameGeneration
    }

    /** Keep one capture in flight and refresh at most ten times per second. */
    fun prepare(now: Long, current: ScreenMetrics): Boolean {
        RenderSystem.assertOnRenderThread()
        check(!closed)
        var changed = if (snapshots == null) collectCompleted() else collectSnapshot(current)
        val active = request
        if (active == null || imageRequest !== active) {
            if (image != null) {
                retireImage()
                ComposeThread.call { mailbox.clearImage() }
                changed = true
            }
            if (active == null) return changed
        }
        if (inFlight) return changed
        if (attemptedRequest === active && attemptedMetrics == current && now - attemptedAt < 100_000_000L)
            return changed
        attemptedRequest = active
        attemptedMetrics = current
        attemptedAt = now

        val maxGuiWidth = (current.guiWidth - 8).coerceIn(1, 320)
        val maxGuiHeight = (current.guiHeight - 8).coerceAtLeast(1)
        val targetWidth = ceil(maxGuiWidth.toDouble() * current.framebufferWidth / current.guiWidth).toInt().coerceAtLeast(1)
        val targetHeight = ceil(maxGuiHeight.toDouble() * current.framebufferHeight / current.guiHeight).toInt().coerceAtLeast(1)
        if (captureMetrics != current) {
            capture?.close()
            capture = NativeGuiCapture(targetWidth, targetHeight)
            captureMetrics = current
        }
        val target = checkNotNull(capture)
        if (snapshots == null) {
            var measured: Layout? = null
            val capturedEpoch = epoch
            val submitted = target.capture(maxGuiWidth, maxGuiHeight, { graphics ->
                drawTooltip(graphics, active, current, maxGuiWidth, maxGuiHeight, targetWidth, targetHeight)
                    .also { measured = it }?.let { it.width to it.height }
            }) { pixels, _, _ ->
                completions.add(Completion(capturedEpoch, active, current, checkNotNull(measured), pixels))
            }
            inFlight = submitted
            if (!submitted && image != null) {
                retireImage()
                ComposeThread.call { mailbox.clearImage() }
                changed = true
            }
            return changed
        }

        val measured = target.renderSized(0, maxGuiWidth, maxGuiHeight, { it.width to it.height }) { graphics ->
            drawTooltip(graphics, active, current, maxGuiWidth, maxGuiHeight, targetWidth, targetHeight)
        }
        if (measured == null) {
            if (image != null) {
                retireImage()
                ComposeThread.call { mailbox.clearImage() }
                changed = true
            }
            return changed
        }
        if (snapshots.immediate) return publishGpu(active, measured, target) || changed
        pending = Pending(target, epoch, active, current, measured)
        return changed
    }

    private fun drawTooltip(graphics: GuiGraphicsExtractor, active: ItemTooltipRequest, current: ScreenMetrics,
                            maxGuiWidth: Int, maxGuiHeight: Int, targetWidth: Int, targetHeight: Int): Layout? {
        val minecraft = Minecraft.getInstance()
        val stack = active.icon.stack
        // NeoForge wraps text 16 units inside screenWidth; the sprite background adds 24.
        val components = ClientHooks.gatherTooltipComponents(stack, Screen.getTooltipFromItem(minecraft, stack),
            stack.tooltipImage, 0, maxGuiWidth - 8, maxGuiHeight, minecraft.font)
        if (components.isEmpty()) return null
        // NeoForge may change the font or cancel; Compose positions the finished image.
        val pre = ClientHooks.onRenderTooltipPre(stack, graphics, 12, 12, current.guiWidth, current.guiHeight,
            components, minecraft.font) { _, _, _, _, _, _ -> org.joml.Vector2i(12, 12) }
        if (pre.isCanceled) return null
        val font = pre.font
        val width = components.maxOf { it.getWidth(font) }
        val height = components.sumOf { it.getHeight(font) } - if (components.size == 1) 2 else 0
        val scale = minOf(1f, maxGuiWidth.toFloat() / (width + 24), maxGuiHeight.toFloat() / (height + 24))
        val pixelWidth = ceil((width + 24) * scale * targetWidth / maxGuiWidth).toInt().coerceIn(1, targetWidth)
        val pixelHeight = ceil((height + 24) * scale * targetHeight / maxGuiHeight).toInt().coerceIn(1, targetHeight)
        graphics.pose().pushMatrix()
        try {
            // The renderer now projects into the measured target, so compensate for its smaller viewport.
            graphics.pose().scale(targetWidth / pixelWidth.toFloat(), targetHeight / pixelHeight.toFloat())
            graphics.pose().scale(scale, scale)
            val style = ClientHooks.onRenderTooltipTexture(stack, graphics, 12, 12, font, components,
                stack.get(DataComponents.TOOLTIP_STYLE)).texture
            TooltipRenderUtil.extractTooltipBackground(graphics, 12, 12, width, height, style)
            var y = 12
            components.forEachIndexed { index, component ->
                component.extractText(graphics, font, 12, y)
                y += component.getHeight(font) + if (index == 0 && components.size > 1) 2 else 0
            }
            y = 12
            components.forEachIndexed { index, component ->
                component.extractImage(font, 12, y, width, height, graphics)
                y += component.getHeight(font) + if (index == 0 && components.size > 1) 2 else 0
            }
        } finally { graphics.pose().popMatrix() }
        return Layout(pixelWidth, pixelHeight, components.size, components.count { it !is ClientTextTooltip })
    }

    private fun collectSnapshot(current: ScreenMetrics): Boolean {
        val work = pending ?: return false
        pending = null
        if (work.epoch != epoch || work.request !== request || work.metrics != current) return false
        return publishGpu(work.request, work.layout, work.capture)
    }

    private fun publishGpu(active: ItemTooltipRequest, measured: Layout, target: NativeGuiCapture): Boolean {
        val gpu = checkNotNull(snapshots)
        val value = gpu.snapshot(target.texture(0), measured.width, measured.height)
        val published = ComposeThread.call { mailbox.publish(active, value) }
        if (!published) {
            gpu.release(value)
            return false
        }
        prepared++
        retireImage()
        image = value
        imageRequest = active
        layout = measured
        return true
    }

    private fun collectCompleted(): Boolean {
        var changed = false
        while (true) {
            val completion = completions.poll() ?: break
            if (completion.epoch != epoch) continue
            inFlight = false
            if (completion.request !== request || completion.metrics != attemptedMetrics) continue
            val value = Image.makeRaster(ImageInfo(completion.layout.width, completion.layout.height,
                ColorType.RGBA_8888, ColorAlphaType.PREMUL), completion.pixels, completion.layout.width * 4)
            val published = ComposeThread.call { mailbox.publish(completion.request, value) }
            if (published) {
                prepared++
                retireImage()
                image = value
                imageRequest = completion.request
                layout = completion.layout
                changed = true
            } else {
                // Statistics count only images handed to Compose, so they balance after close.
                value.close()
            }
        }
        return changed
    }

    private fun retireImage() {
        image?.let { old ->
            if (snapshots == null) FrameRetirement.afterFrame { old.close() }
            else snapshots.release(old)
            retired++
        }
        image = null
        imageRequest = null
        layout = null
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        epoch++
        inFlight = false
        pending = null
        completions.clear()
        capture?.close()
        capture = null
        captureMetrics = null
        request = null
        attemptedRequest = null
        attemptedMetrics = null
        retireImage()
        ComposeThread.call { mailbox.clearImage() }
    }

    override fun close() {
        if (closed) return
        reset()
        closed = true
    }
}
