package dev.composemc.testing.render

import dev.composemc.host.UiSession
import dev.composemc.platform.Viewport
import dev.composemc.render.RecordedFrame
import java.io.File

/** Shared scene, resize cases, reference pixels and reports for real-device backend probes. */
object RendererAcceptance {
    fun verify(directory: File, backend: String, probe: (RecordedFrame, ByteArray) -> RendererProbeResult): String {
        val viewports = listOf(Viewport(320, 240), Viewport(384, 288))
        val reports = mutableListOf<String>()
        UiSession(viewports.first(), content = { RendererFixture() }).use { session ->
            viewports.forEachIndexed { index, viewport ->
                session.resize(viewport)
                checkNotNull(session.frame(1_000_000L + index)).use { frame ->
                    val expected = RendererPixels.reference(frame)
                    val label = "${viewport.width}x${viewport.height}"
                    RendererPixels.save(File(directory, "composemc-renderer-cpu-$label.png"), expected, viewport.width, viewport.height)
                    val result = probe(frame, expected)
                    RendererPixels.save(File(directory, "composemc-renderer-$backend-$label.png"), result.rgba, viewport.width, viewport.height)
                    reports += "$label: ${result.cycles} cycles, worst different pixels=${result.worstDifferentPixels}, mean channel error=${result.worstMeanChannelError}"
                }
            }
        }
        return "$backend renderer: CPU pixel reference, alpha, clip, text, resize, reset and close; ${reports.joinToString("; ")}"
    }
}
