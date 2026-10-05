@file:Suppress("DEPRECATION")

package dev.compixel.forge.item

import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.ComposeThread
import dev.compixel.bridge.NativeImageRefresh
import dev.compixel.forge.render.ScreenFrameRenderer
import dev.compixel.host.ScreenMetrics
import dev.compixel.render.GpuPhase
import dev.compixel.render.NativeImageOwner
import kotlin.math.ceil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.ClientHooks

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

/** One visible tooltip, refreshed at most ten times per second, with no per-item tooltip cache. */
internal class NativeTooltipRenderer(
    private val backend: ScreenFrameRenderer,
    private val mailbox: ItemTooltipMailbox,
) : AutoCloseable {
    private data class Layout(val width: Int, val height: Int, val components: Int, val richComponents: Int)

    private data class PreparedTooltip(
        val components: List<ClientTooltipComponent>,
        val font: Font,
        val width: Int,
        val height: Int,
        val scale: Float,
        val layout: Layout,
    )

    private val layoutTarget = NativeGuiRenderTarget(backend)
    private val target = NativeGuiRenderTarget(backend)
    private var request: ItemTooltipRequest? = null
    private var preparedRequest: ItemTooltipRequest? = null
    private var metrics: ScreenMetrics? = null
    private val images = NativeImageOwner { value -> backend.releaseNativeImage(value) }
    private val image
        get() = images.image

    private val refresh = NativeImageRefresh.every(100)
    private var layout: Layout? = null
    private var updated = Long.MIN_VALUE
    private var prepared = 0L
    private var retired = 0L
    private var generation = 0L
    val statistics
        get() =
            NativeTooltipStatistics(
                image != null,
                layout?.width ?: 0,
                layout?.height ?: 0,
                layout?.components ?: 0,
                layout?.richComponents ?: 0,
                prepared,
                retired,
                generation,
            )

    fun recorded(frameGeneration: Long) {
        request = ComposeThread.call { mailbox.request }
        generation = frameGeneration
    }

    fun prepare(now: Long, current: ScreenMetrics): Boolean {
        RenderSystem.assertOnRenderThread()
        val active = request
        if (active == null) {
            if (preparedRequest == null && image == null) return false
            val changed = image != null
            reset()
            return changed
        }
        if (preparedRequest === active && metrics == current && !refresh.isDue(now, updated)) return false
        val minecraft = Minecraft.getInstance()
        val guiWidth = (current.guiWidth - 8).coerceIn(1, 320)
        val guiHeight = (current.guiHeight - 8).coerceAtLeast(1)
        val targetWidth = ceil(guiWidth * current.guiScale).toInt()
        val targetHeight = ceil(guiHeight * current.guiScale).toInt()
        // The pre hook can change the font or components. Run it once in a tiny isolated target,
        // then allocate the actual capture target from the resulting bounds.
        val (_, preparedTooltip) =
            layoutTarget.draw(1, 1, guiWidth.toFloat(), guiHeight.toFloat(), GpuPhase.TOOLTIP) { graphics ->
                val stack = active.icon.stack
                // Use native wrapping, rich component factories, custom fonts and event hooks.
                // Popup placement belongs to Compose; native render coordinates are target-local.
                val components =
                    ClientHooks.gatherTooltipComponents(
                        stack,
                        Screen.getTooltipFromItem(minecraft, stack),
                        stack.tooltipImage,
                        0,
                        guiWidth + 8,
                        guiHeight,
                        minecraft.font,
                    )
                if (components.isEmpty()) return@draw null
                val pre =
                    ClientHooks.onRenderTooltipPre(
                        stack,
                        graphics,
                        4,
                        4,
                        current.guiWidth,
                        current.guiHeight,
                        components,
                        minecraft.font,
                        DefaultTooltipPositioner.INSTANCE,
                    )
                if (pre.isCanceled || components.isEmpty()) return@draw null
                val font = pre.font
                val width = components.maxOf { it.getWidth(font) }
                val height = components.sumOf { it.height } - if (components.size == 1) 2 else 0
                val scale = minOf(1f, guiWidth.toFloat() / (width + 8), guiHeight.toFloat() / (height + 8))
                PreparedTooltip(
                    components,
                    font,
                    width,
                    height,
                    scale,
                    Layout(
                        ceil((width + 8) * scale * current.guiScale).toInt().coerceIn(1, targetWidth),
                        ceil((height + 8) * scale * current.guiScale).toInt().coerceIn(1, targetHeight),
                        components.size,
                        components.count { it !is ClientTextTooltip },
                    ),
                )
            }
        val measured = preparedTooltip?.layout
        val replacement = preparedTooltip?.let { plan ->
            val (source, _) =
                target.draw(
                    plan.layout.width,
                    plan.layout.height,
                    plan.layout.width / current.guiScale,
                    plan.layout.height / current.guiScale,
                    GpuPhase.TOOLTIP,
                ) { graphics ->
                    drawTooltip(graphics, active.icon.stack, plan)
                }
            backend.copyNativeImage(source, plan.layout.width, plan.layout.height)
        }
        val published = ComposeThread.call { mailbox.publish(active, replacement) }
        val changed = image != null || replacement != null
        retireImage()
        if (published) {
            images.replace(replacement)
            layout = measured
        } else if (replacement != null) {
            backend.releaseNativeImage(replacement)
            retired++
        }
        if (replacement != null) prepared++
        preparedRequest = active
        metrics = current
        updated = now
        return changed
    }

    private fun drawTooltip(graphics: GuiGraphics, stack: ItemStack, plan: PreparedTooltip) {
        val components = plan.components
        val font = plan.font
        val width = plan.width
        val height = plan.height
        val colors = ClientHooks.onRenderTooltipColor(stack, graphics, 4, 4, font, components)
        graphics.pose().pushPose()
        try {
            graphics.pose().scale(plan.scale, plan.scale, 1f)
            graphics.drawManaged {
                TooltipRenderUtil.renderTooltipBackground(
                    graphics,
                    4,
                    4,
                    width,
                    height,
                    400,
                    colors.backgroundStart,
                    colors.backgroundEnd,
                    colors.borderStart,
                    colors.borderEnd,
                )
            }
            graphics.pose().translate(0f, 0f, 400f)
            var y = 4
            components.forEachIndexed { index, component ->
                component.renderText(font, 4, y, graphics.pose().last().pose(), graphics.bufferSource())
                y += component.height + if (index == 0) 2 else 0
            }
            y = 4
            components.forEachIndexed { index, component ->
                component.renderImage(font, 4, y, graphics)
                y += component.height + if (index == 0) 2 else 0
            }
        } finally {
            graphics.pose().popPose()
        }
    }

    private fun retireImage() {
        if (image != null) retired++
        images.close()
        layout = null
    }

    fun reset() {
        RenderSystem.assertOnRenderThread()
        ComposeThread.call { mailbox.clearImage() }
        retireImage()
        preparedRequest = null
        metrics = null
    }

    override fun close() {
        reset()
        request = null
        layoutTarget.close()
        target.close()
    }
}
