package dev.compixel.development.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.drawing.MinecraftNativeDrawing
import dev.compixel.forge.drawing.NativeDrawing
import dev.compixel.forge.drawing.NativeRefresh
import dev.compixel.testing.suite.ScreenPixels
import dev.compixel.testing.ui.*
import kotlin.math.roundToInt
import net.minecraft.network.chat.Component

private class NativeDrawingSamples {
    var staticDraws = 0
    var tickingDraws = 0
    val sizes = mutableSetOf<IntSize>()
    val still =
        NativeDrawing.create(
            "rectangular native acceptance",
            { context ->
                staticDraws++
                sizes += IntSize(context.pixelWidth, context.pixelHeight)
                val graphics = context.graphics
                check(graphics.guiWidth() == context.width && graphics.guiHeight() == context.height) {
                    "Native graphics used the window viewport"
                }
                graphics.fill(0, 0, context.width, context.height, DRAWING_RED)
                graphics.enableScissor(6, 5, context.width / 2, context.height - 5)
                graphics.fill(-8, -8, context.width + 8, context.height + 8, DRAWING_GREEN)
                graphics.disableScissor()
                graphics.fill(context.width * 3 / 4, 0, context.width, context.height, DRAWING_BLUE)
            },
            NativeRefresh.STATIC,
        )
    val preview =
        NativeDrawing.create(
            "native entity preview",
            { context ->
                context.graphics.fill(0, 0, context.width, context.height, DRAWING_BACKGROUND)
                dev.compixel.development.SuitePlatform.nativeDrawingPreview(context)
            },
            NativeRefresh.STATIC,
        )
    val ticking =
        NativeDrawing.create(
            "rectangular native ticking",
            { context ->
                val color = DRAWING_TICKING[tickingDraws++ % DRAWING_TICKING.size]
                context.graphics.fill(0, 0, context.width, context.height, color)
            },
            NativeRefresh.GAME_TICK,
        )
}

internal class NativeDrawingVisualScreen
private constructor(
    private val samples: NativeDrawingSamples,
    val model: NativeDrawingVisualModel,
) :
    ComposeScreen(
        Component.literal("Native rectangular drawing acceptance"),
        content = {
            NativeDrawingVisualScene(
                model,
                { modifier -> MinecraftNativeDrawing(samples.still, modifier) },
                { modifier -> MinecraftNativeDrawing(samples.ticking, modifier) },
                { modifier -> MinecraftNativeDrawing(samples.preview, modifier) },
            )
        },
    ) {
    constructor() : this(NativeDrawingSamples(), ComposeThread.call { NativeDrawingVisualModel() })

    override fun isUiWindowFocused() = dev.compixel.development.SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    val staticDraws
        get() = samples.staticDraws

    val tickingDraws
        get() = samples.tickingDraws

    fun bounds(): Map<String, Rect> = ComposeThread.call { model.bounds() }

    fun verifyDimensions() {
        for (name in listOf("wide", "portrait", "clipped")) {
            val bounds = bounds().getValue(name)
            check(IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()) in samples.sizes) {
                "Native image resolution does not match $name: $bounds / " + samples.sizes
            }
        }
        check(samples.sizes.any { it.width > 256 }) { "The rectangle was clamped to icon resolution" }
    }

    fun verifyPixels(pixels: ScreenPixels, bounds: Map<String, Rect>) =
        verifyNativeDrawingPixels(bounds, pixels.width, pixels.height, pixels::argb)
}
