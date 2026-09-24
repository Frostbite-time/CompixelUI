package dev.composemc.development.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.composemc.bridge.ComposeThread
import dev.composemc.neoforge.NeoForgeComposeScreen
import dev.composemc.testing.suite.ScreenPixels
import net.minecraft.network.chat.Component

internal class PortValidationModel {
    var clicks by mutableIntStateOf(0)
    var text by mutableStateOf("")
}

/** Deliberately asymmetric fixture, with a real Compose click target and alpha patch. */
internal class PortValidationScreen(val model: PortValidationModel = ComposeThread.call { PortValidationModel() }) :
    NeoForgeComposeScreen(Component.literal("Port validation"), content = {
        Box(Modifier.fillMaxSize().background(Color(0xFF204060))) {
            Box(Modifier.align(Alignment.TopStart).padding(16.dp).size(40.dp)
                .background(if (model.clicks == 0) Color.Red else Color.Yellow).clickable { model.clicks++ })
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(40.dp).background(Color.Green))
            Box(Modifier.align(Alignment.BottomStart).padding(16.dp).size(40.dp).background(Color.Blue))
            Box(Modifier.align(Alignment.Center).size(40.dp).background(Color.White.copy(alpha = 0.5f)))
            BasicTextField(model.text, { model.text = it },
                Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(120.dp, 30.dp).background(Color.White))
        }
    }) {
    override fun isUiWindowFocused() = dev.composemc.development.SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    /** [clicked] is the expected state of the top-left target when the frame was captured. */
    fun verifyPixels(pixels: ScreenPixels, scale: Int, clicked: Boolean) {
        fun pixel(x: Int, y: Int, expected: Int) {
            val actual = pixels.argb(x, y)
            check((0..2).all { channel ->
                kotlin.math.abs(((actual ushr (8 * channel)) and 255) - ((expected ushr (8 * channel)) and 255)) <= 4
            }) { "Pixel ($x,$y): expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}" }
        }
        val inset = 32 * scale
        pixel(inset, inset, if (clicked) 0xFFFFFF00.toInt() else 0xFFFF0000.toInt())
        pixel(pixels.width - inset, inset, 0xFF00FF00.toInt())
        pixel(inset, pixels.height - inset, 0xFF0000FF.toInt())
        pixel(pixels.width / 2, pixels.height / 2, 0xFF90A0B0.toInt())
    }
}
