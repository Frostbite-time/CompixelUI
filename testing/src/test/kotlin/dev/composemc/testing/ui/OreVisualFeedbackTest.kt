package dev.composemc.testing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.composemc.host.UiSession
import dev.composemc.platform.*
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.button.OreIconButton
import dev.composemc.ui.ore.input.OreTextField
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.layout.OreSurfaceStyle
import dev.composemc.ui.ore.selection.OreRadioButton
import dev.composemc.ui.ore.selection.OreTabButton
import dev.composemc.ui.ore.theme.OreTheme
import dev.composemc.ui.ore.theme.OreColors
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class OreVisualFeedbackTest {
    @Test fun `custom icon canvas renders with the themed foreground including disabled styles`() {
        val colors = OreColors(text = Color.Magenta, ink = Color.Cyan, disabledText = Color.Yellow, mutedText = Color.Green)
        for (style in OreButtonStyle.entries) for (enabled in listOf(true, false)) {
            UiSession(Viewport(40, 40)) {
                OreTheme(colors = colors) {
                    OreIconButton("Custom", {}, Modifier.size(28.dp), enabled, style) { contentColor ->
                        Canvas(Modifier.size(8.dp)) { drawRect(contentColor) }
                    }
                }
            }.use { session ->
                Surface.makeRasterN32Premul(40, 40).use { surface ->
                    repeat(3) { frame ->
                        session.frame(1_000_000_000L + frame * 20_000_000L)?.use {
                            surface.canvas.clear(0); it.draw(surface.canvas)
                        }
                    }
                    val image = surface.makeImageSnapshot().use {
                        it.encodeToData()!!.use { data -> ImageIO.read(ByteArrayInputStream(data.bytes)) }
                    }
                    val expected = when {
                        !enabled -> if (style == OreButtonStyle.Quiet) colors.mutedText else colors.disabledText
                        style == OreButtonStyle.Secondary -> colors.ink
                        else -> colors.text
                    }
                    assertEquals(expected.toArgb(), image.getRGB(14, 14), "Custom visual missing or wrong foreground: $style enabled=$enabled")
                }
            }
        }
    }

    @Test fun `depth panels keep vertical sides flat and separate the two pixel bands from their casing`() {
        for (density in listOf(1f,1.5f,3f)) {
            val width=(200*density).toInt();val height=(45*density).toInt()
            UiSession(Viewport(width,height,density)) {OreTheme {Row {
                OreSurface(Modifier.size(100.dp,40.dp),color=OreTheme.colors.panel,style=OreSurfaceStyle.Inset) {}
                OreSurface(Modifier.size(100.dp,40.dp),color=OreTheme.colors.panel,style=OreSurfaceStyle.Raised) {}
            }}}.use {session->Surface.makeRasterN32Premul(width,height).use {surface->
                repeat(3){session.frame(1_000_000_000L+it*20_000_000)?.use {f->surface.canvas.clear(0);f.draw(surface.canvas)}}
                val image=surface.makeImageSnapshot().use {it.encodeToData()!!.use {data->ImageIO.read(ByteArrayInputStream(data.bytes))}}
                val border=kotlin.math.round(density).toInt().coerceAtLeast(1)
                val band=kotlin.math.round(2*density).toInt()
                val panelWidth=(100*density).toInt();val panelHeight=(40*density).toInt();val mid=(20*density).toInt()
                for(offset in listOf(0,panelWidth)) {
                    assertEquals(0xFF313233.toInt(),image.getRGB(offset+border,mid),"Unexpected left lighting at $density")
                    assertEquals(0xFF313233.toInt(),image.getRGB(offset+panelWidth-border-1,mid),"Unexpected right lighting at $density")
                }
                val x=(50*density).toInt()
                for(y in border until border+band)assertEquals(0xFF1E1E1F.toInt(),image.getRGB(x,y))
                assertEquals(0xFF313233.toInt(),image.getRGB(x,border+band))
                for(y in panelHeight-border-band until panelHeight-border)assertEquals(0xFF242426.toInt(),image.getRGB(panelWidth+x,y))
                assertEquals(0xFF101112.toInt(),image.getRGB(panelWidth+x,panelHeight-1))
            }}
        }
    }

    @Test fun `diamond radio retains crisp edges and a white selected center`() {
        for(density in listOf(1f,1.5f,3f)) {
            val width=(30*density).toInt();val height=(20*density).toInt()
            UiSession(Viewport(width,height,density)){OreTheme{OreRadioButton(true,{})}}.use {session->
                Surface.makeRasterN32Premul(width,height).use {surface->
                    repeat(3){session.frame(1_000_000_000L+it*20_000_000)?.use {f->surface.canvas.clear(0);f.draw(surface.canvas)}}
                    val image=surface.makeImageSnapshot().use {it.encodeToData()!!.use {data->ImageIO.read(ByteArrayInputStream(data.bytes))}}
                    val colors=mutableSetOf<Int>()
                    for(y in 0 until height)for(x in 0 until width){
                        val argb=image.getRGB(x,y);val alpha=argb ushr 24
                        assertTrue(alpha==0||alpha==255,"Smoothed radio edge at $density: alpha=$alpha")
                        if(alpha==255)colors+=argb
                    }
                    assertTrue(0xFF3C8527.toInt() in colors && 0xFFF2F3F4.toInt() in colors && 0xFF214515.toInt() in colors)
                    assertEquals(0,image.getRGB(0,0) ushr 24)
                }
            }
        }
    }
    @Test fun `inset and raised surfaces reverse their bevel lighting even with the same fill`() {
        val density=3f
        UiSession(Viewport(600,150,density)) {OreTheme {
            Row {
                OreSurface(Modifier.size(100.dp,40.dp),color=OreTheme.colors.panel,style=OreSurfaceStyle.Inset) {}
                OreSurface(Modifier.size(100.dp,40.dp),color=OreTheme.colors.panel,style=OreSurfaceStyle.Raised) {}
            }
        }}.use {session->
            Surface.makeRasterN32Premul(600,150).use {surface->
                repeat(3){session.frame(1_000_000_000L+it*20_000_000)?.use {f->surface.canvas.clear(0);f.draw(surface.canvas)}}
                val image=surface.makeImageSnapshot().use {it.encodeToData()!!.use {data->ImageIO.read(ByteArrayInputStream(data.bytes))}}
                fun brightness(x:Int,y:Int)=image.getRGB(x,y) and 255
                assertTrue(brightness(150,4)<brightness(150,115),"Inset top must be darker than its lower edge")
                assertTrue(brightness(450,4)>brightness(450,115),"Raised top must be lighter than its lower edge")
            }
        }
    }

    @Test fun `joined button keeps a lower ledge at rest and lowers the whole casing when pressed`() {
        UiSession(Viewport(600,130,3f)) {OreTheme {
            OreTabButton(listOf("A","B"),1,{},Modifier.width(180.dp))
        }}.use {session->
            Surface.makeRasterN32Premul(600,130).use {surface->
                var time=1_000_000_000L
                fun image():BufferedImage {
                    repeat(3){time+=20_000_000;session.frame(time)?.use {f->surface.canvas.clear(0);f.draw(surface.canvas)}}
                    return surface.makeImageSnapshot().use {it.encodeToData()!!.use {data->ImageIO.read(ByteArrayInputStream(data.bytes))}}
                }
                val rest=image()
                assertEquals(0xFF58585A.toInt(),rest.getRGB(60,69),"Unselected button lost its lower ledge")
                session.pointer(PointerInput(PointerAction.PRESS,60f,36f,MouseButton.LEFT))
                val pressed=image()
                assertEquals(0,pressed.getRGB(60,2) ushr 24,"Pressed/focused border must move with the casing")
                assertTrue(pressed.getRGB(60,69)!=rest.getRGB(60,69),"Face did not move into the ledge")
            }
        }
    }
    @Test fun `empty field cursor stays vertically aligned after first character with a CJK placeholder`() {
        for (density in listOf(1.5f, 3f, 4f)) {
            val value=mutableStateOf("")
            val width=(250*density).toInt();val height=(45*density).toInt()
            UiSession(Viewport(width,height,density)) {
                OreTheme { OreTextField(value.value,{value.value=it},Modifier.width(240.dp),placeholder="错误状态",isError=true) }
            }.use { session ->
                Surface.makeRasterN32Premul(width,height).use { surface ->
                    var time=1_000_000_000L
                    fun render():BufferedImage {
                        repeat(3){time+=16_666_667;session.frame(time)?.use {frame->surface.canvas.clear(0);frame.draw(surface.canvas)}}
                        return surface.makeImageSnapshot().use {image->image.encodeToData()!!.use {ImageIO.read(ByteArrayInputStream(it.bytes))}}
                    }
                    render()
                    val x=40*density;val y=10*density
                    session.pointer(PointerInput(PointerAction.PRESS,x,y,MouseButton.LEFT))
                    session.pointer(PointerInput(PointerAction.RELEASE,x,y,MouseButton.LEFT))
                    val empty=render()
                    session.commitText("1")
                    val typed=render()
                    val directory=File("build/feedback-visuals").apply {mkdirs()}
                    ImageIO.write(empty,"png",File(directory,"cursor-empty-$density.png"))
                    ImageIO.write(typed,"png",File(directory,"cursor-typed-$density.png"))
                    val a=cursorSpan(empty,density);val b=cursorSpan(typed,density)
                    println("Cursor density=$density empty=$a typed=$b")
                    assertTrue(kotlin.math.abs((a.first+a.last)-(b.first+b.last))<=2,
                        "Cursor shifted vertically at $density: empty=$a typed=$b")
                }
            }
        }
    }

    private fun cursorSpan(image:BufferedImage,density:Float):IntRange {
        var best=IntRange.EMPTY
        for(x in (4*density).toInt() until (35*density).toInt()) {
            var start=-1
            for(y in 0 until image.height) {
                val argb=image.getRGB(x,y)
                val white=(argb ushr 24)>240 && (argb shr 16 and 255)>230 && (argb shr 8 and 255)>230 && (argb and 255)>230
                if(white&&start<0)start=y
                if((!white||y==image.height-1)&&start>=0) {
                    val end=if(white)y else y-1
                    if(end-start>best.last-best.first)best=start..end
                    start=-1
                }
            }
        }
        check(best.count()>=5*density) {"No cursor detected: $best"}
        return best
    }
}
