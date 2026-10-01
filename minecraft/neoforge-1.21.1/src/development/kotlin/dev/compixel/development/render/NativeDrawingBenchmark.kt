package dev.compixel.development.render

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.compixel.forge.drawing.MinecraftNativeDrawing
import dev.compixel.forge.drawing.NativeDrawing
import dev.compixel.forge.drawing.NativeRefresh
import dev.compixel.testing.suite.BenchmarkKind

/** Four independent rectangular viewports, including images wider than the icon resolution limit. */
internal class NativeDrawingBenchmark(kind: BenchmarkKind) {
    val active = kind == BenchmarkKind.DRAWING_STATIC || kind == BenchmarkKind.DRAWING_ANIMATED
    private val drawings =
        if (!active) emptyList()
        else
            List(4) { index ->
                var frame = 0
                NativeDrawing.create(
                    "Native benchmark rectangle $index",
                    { context ->
                        val graphics = context.graphics
                        graphics.fill(0, 0, context.width, context.height, 0xFF243C50.toInt())
                        repeat(12) { stripe ->
                            val x = (stripe * context.width / 12 + frame) % context.width
                            graphics.fill(x, 4, minOf(x + 5, context.width), context.height - 4, 0xFF80D4C0.toInt())
                        }
                        frame++
                    },
                    if (kind == BenchmarkKind.DRAWING_STATIC) NativeRefresh.STATIC else NativeRefresh.FRAME,
                )
            }

    @Composable
    fun Content() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MinecraftNativeDrawing(drawings[0], Modifier.size(216.dp, 40.dp))
                MinecraftNativeDrawing(drawings[1], Modifier.size(128.dp, 80.dp))
                MinecraftNativeDrawing(drawings[3], Modifier.size(160.dp, 54.dp))
            }
            MinecraftNativeDrawing(drawings[2], Modifier.size(54.dp, 144.dp))
        }
    }
}
