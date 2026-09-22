package dev.composemc.development

import dev.composemc.render.gl.testing.OpenGlRendererProbe

import dev.composemc.neoforge.*

import com.mojang.blaze3d.platform.NativeImage
import dev.composemc.demo.testing.RendererFixture
import dev.composemc.host.UiSession
import dev.composemc.platform.Viewport
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import java.io.File

internal fun verifyOpenGlRenderer(): String {
    val viewport = Viewport(320, 240)
    val background = 0xFF203040.toInt()
    UiSession(viewport, content = { RendererFixture() }).use { session ->
        checkNotNull(session.frame(1_000_000)).use { frame ->
            Surface.makeRasterN32Premul(viewport.width, viewport.height).use { surface ->
                surface.canvas.clear(background)
                frame.draw(surface.canvas)
                surface.makeImageSnapshot().use { image ->
                    checkNotNull(image.encodeToData()).use { encoded ->
                        NativeImage.read(ByteArrayInputStream(encoded.bytes)).use { expected ->
                            expected.writeToFile(File(Minecraft.getInstance().gameDirectory, "composemc-renderer-cpu.png"))
                            val rgba = ByteArray(viewport.width * viewport.height * 4)
                            // 26.x NativeImage uses ARGB integers; the shared GL probe uses RGBA bytes.
                            val channelShifts = intArrayOf(16, 8, 0, 24)
                            for (y in 0 until viewport.height) for (x in 0 until viewport.width) {
                                val color = expected.getPixel(x, y)
                                for (channel in 0..3) rgba[(y * viewport.width + x) * 4 + channel] = (color ushr channelShifts[channel]).toByte()
                            }
                            val result = OpenGlRendererProbe.verify(frame, rgba, background)
                            NativeImage(viewport.width, viewport.height, false).use { actual ->
                                for (y in 0 until viewport.height) for (x in 0 until viewport.width) {
                                    val offset = (y * viewport.width + x) * 4
                                    var color = 0
                                    for (channel in 0..3) color = color or ((result.rgba[offset + channel].toInt() and 255) shl channelShifts[channel])
                                    actual.setPixel(x, y, color)
                                }
                                actual.writeToFile(File(Minecraft.getInstance().gameDirectory, "composemc-renderer-gl.png"))
                            }
                            return "GL state, pixel-transfer isolation, alpha, clip, text, reset and close: " +
                                result.cycles + " contexts, worst different pixels=" + result.worstDifferentPixels +
                                ", mean channel error=" + result.worstMeanChannelError
                        }
                    }
                }
            }
        }
    }
}
