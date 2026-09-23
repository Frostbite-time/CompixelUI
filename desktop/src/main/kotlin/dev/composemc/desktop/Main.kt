package dev.composemc.desktop

import dev.composemc.bridge.ComposeThread
import dev.composemc.demo.preview.DemoModel
import dev.composemc.demo.preview.OreDemoScreen as DemoScreen
import dev.composemc.demo.preview.Fixture
import dev.composemc.demo.preview.DemoPage
import dev.composemc.host.UiSession
import dev.composemc.platform.*
import org.jetbrains.skia.Surface
import java.awt.*
import java.awt.event.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.*

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--smoke") {
        smoke(File(args.getOrElse(1) { "build/screenshots" }))
        return
    }
    EventQueue.invokeLater { openPreview() }
}

private fun smoke(directory: File) {
    directory.mkdirs()
    val model = ComposeThread.call { DemoModel() }
    UiSession(Viewport(960, 640), content = { DemoScreen(model) { modifier ->
        dev.composemc.demo.preview.ItemBrowserDemo(model.itemBrowser, List(256) { "Item $it" }, modifier) { index, imageModifier ->
            dev.composemc.ui.ore.display.OreIcon(dev.composemc.ui.ore.display.OreGlyph.entries[index % dev.composemc.ui.ore.display.OreGlyph.entries.size], imageModifier)
        }
    } }).use { session ->
        println("Compose thread: ${session.diagnosticThread()}")
        val cases = listOf(
            Triple("settings-en", Viewport(960, 640), { model.page = DemoPage.Settings }),
            Triple("settings-zh", Viewport(960, 640), { model.locale = "zh_cn" }),
            Triple("catalog-100k", Viewport(960, 640), { model.page = DemoPage.Catalog; model.count = 100000 }),
            Triple("compact", Viewport(360, 640), { model.locale = "en_us"; model.page = DemoPage.Settings }),
            Triple("scale-2", Viewport(1280, 960, 2f), { model.page = DemoPage.Settings }),
            Triple("dialog", Viewport(960, 640), { model.dialog = true }),
            Triple("empty", Viewport(960, 640), { model.dialog = false; model.page = DemoPage.Catalog; model.fixture = Fixture.EMPTY }),
        ) + (listOf(DemoPage.Buttons) + dev.composemc.testing.ui.oreComponentPages).flatMap { page ->
            listOf(
                Triple("ore-${page.name.lowercase()}-en",Viewport(1100,840,2f),{model.page=page;model.locale="en_us";model.dialog=false}),
                Triple("ore-${page.name.lowercase()}-zh-compact",Viewport(360,640),{model.page=page;model.locale="zh_cn";model.dialog=false}),
                Triple("ore-${page.name.lowercase()}-zh-150",Viewport(825,630,1.5f),{model.page=page;model.locale="zh_cn";model.dialog=false}),
            )
        }
        var time = 1_000_000_000L
        cases.forEach { (name, viewport, configure) ->
            session.post(Runnable { configure() })
            session.resize(viewport)
            Surface.makeRasterN32Premul(viewport.width, viewport.height).use { surface ->
                repeat(8) {
                    time += 16_666_667
                    session.frame(time)?.use { frame -> surface.canvas.clear(0); frame.draw(surface.canvas) }
                }
                if(model.page==DemoPage.Menus) {
                    session.key(KeyInput(UiKey.ESCAPE,true));session.key(KeyInput(UiKey.ESCAPE,false))
                    val point=ComposeThread.call {model.bounds.getValue("menu-button").center}
                    session.pointer(PointerInput(PointerAction.MOVE,point.x,point.y))
                    session.pointer(PointerInput(PointerAction.PRESS,point.x,point.y,MouseButton.LEFT))
                    session.pointer(PointerInput(PointerAction.RELEASE,point.x,point.y,MouseButton.LEFT))
                    repeat(8) {time+=20_000_000;session.frame(time)?.use {frame->surface.canvas.clear(0);frame.draw(surface.canvas)}}
                }
                if(model.page==DemoPage.Tooltips) {
                    for(id in listOf("tooltip-anchor","tooltip-term","tooltip-item")) {
                        val point=ComposeThread.call {model.bounds.getValue(id).center}
                        session.pointer(PointerInput(PointerAction.MOVE,point.x,point.y))
                        repeat(45) {time+=20_000_000;session.frame(time)?.use {frame->surface.canvas.clear(0);frame.draw(surface.canvas)}}
                    }
                    check(ComposeThread.call {"tooltip-level-3" in model.bounds}) {"Nested tooltip preview did not reach level 3"}
                    for(id in listOf("tooltip-level-1","tooltip-level-2","tooltip-level-3")) {
                        val bounds=ComposeThread.call {model.bounds.getValue(id)}
                        check(bounds.left>=0&&bounds.top>=0&&bounds.right<=viewport.width&&bounds.bottom<=viewport.height) {"Tooltip outside viewport: $id $bounds"}
                    }
                }
                surface.makeImageSnapshot().use { image ->
                    image.encodeToData()!!.use { data ->
                        val bytes = data.bytes
                        val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
                        val colors = mutableSetOf<Int>()
                        for (y in 0 until viewport.height step 8) for (x in 0 until viewport.width step 8) colors.add(bitmap.getRGB(x, y))
                        check(colors.size > 12) { "Blank/degenerate fixture: $name (${colors.size} colors)" }
                        File(directory, "$name.png").writeBytes(bytes)
                        println("PASS $name: ${viewport.width}x${viewport.height}, ${colors.size} sampled colors")
                    }
                }
                if (model.page == DemoPage.Selection && name.endsWith("-en")) {
                    fun captureState(suffix: String) {
                        repeat(3) { time += 20_000_000; session.frame(time)?.use { frame -> surface.canvas.clear(0); frame.draw(surface.canvas) } }
                        surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { data -> File(directory, "ore-selection-$suffix.png").writeBytes(data.bytes) } }
                    }
                    val tabs = ComposeThread.call { model.bounds.getValue("choices-tabs") }
                    val x = tabs.left + tabs.width * .375f
                    session.pointer(PointerInput(PointerAction.MOVE, x, tabs.center.y))
                    session.pointer(PointerInput(PointerAction.PRESS, x, tabs.center.y, MouseButton.LEFT))
                    captureState("pressed")
                    session.pointer(PointerInput(PointerAction.RELEASE, x, tabs.center.y, MouseButton.LEFT))
                    captureState("focused")
                    val radio = ComposeThread.call { model.bounds.getValue("radio-2").center }
                    session.pointer(PointerInput(PointerAction.MOVE, radio.x, radio.y))
                    captureState("radio-hovered")
                    session.pointer(PointerInput(PointerAction.PRESS, radio.x, radio.y, MouseButton.LEFT))
                    captureState("radio-pressed")
                    session.pointer(PointerInput(PointerAction.RELEASE, radio.x, radio.y, MouseButton.LEFT))
                    captureState("radio-focused")
                    session.pointer(PointerInput(PointerAction.PRESS, radio.x, radio.y, MouseButton.LEFT))
                    captureState("radio-selected-pressed")
                    session.pointer(PointerInput(PointerAction.RELEASE, radio.x, radio.y, MouseButton.LEFT))
                }
            }
        }
    }
    println("Smoke render complete: ${directory.absolutePath}")
}

private fun openPreview() {
    val window = JFrame("Compose MC | Host Preview")
    val model = DemoModel()
    val clipboard = object : ClipboardPort {
        override fun readText(): String = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrDefault("")
        override fun writeText(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
    val session = UiSession(Viewport(960, 640), clipboard, content = { DemoScreen(model) })
    var surface = Surface.makeRasterN32Premul(960, 640)
    var size = Dimension(960, 640)
    var bitmap: java.awt.image.BufferedImage? = null
    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) { super.paintComponent(g); bitmap?.let { g.drawImage(it, 0, 0, width, height, null) } }
    }.apply { preferredSize = size; minimumSize = Dimension(320, 240); isFocusable = true; background = Color(0x171B1D) }
    fun pointer(e: MouseEvent, action: PointerAction, scroll: Float = 0f) {
        if (action == PointerAction.PRESS) panel.requestFocusInWindow()
        session.pointer(PointerInput(action, e.x.toFloat(), e.y.toFloat(), when (e.button) {
            MouseEvent.BUTTON1 -> MouseButton.LEFT; MouseEvent.BUTTON2 -> MouseButton.MIDDLE; MouseEvent.BUTTON3 -> MouseButton.RIGHT; else -> null
        }, scrollY = scroll, modifiers = e.modifiers()))
    }
    panel.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = pointer(e, PointerAction.PRESS)
        override fun mouseReleased(e: MouseEvent) = pointer(e, PointerAction.RELEASE)
        override fun mouseExited(e: MouseEvent) = pointer(e, PointerAction.EXIT)
    })
    panel.addMouseMotionListener(object : MouseMotionAdapter() {
        override fun mouseMoved(e: MouseEvent) = pointer(e, PointerAction.MOVE)
        override fun mouseDragged(e: MouseEvent) = pointer(e, PointerAction.MOVE)
    })
    panel.addMouseWheelListener { pointer(it, PointerAction.SCROLL, it.preciseWheelRotation.toFloat()) }
    panel.focusTraversalKeysEnabled = false
    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) { session.key(KeyInput(e.uiKey(), true, e.modifiers())) }
        override fun keyReleased(e: KeyEvent) { session.key(KeyInput(e.uiKey(), false, e.modifiers())) }
        override fun keyTyped(e: KeyEvent) {
            if (!e.isControlDown && !e.isMetaDown && !Character.isISOControl(e.keyChar)) session.commitText(e.keyChar.toString())
        }
    })
    val timer = Timer(16) {
        if (panel.width <= 0 || panel.height <= 0) return@Timer
        if (size != panel.size) {
            size = panel.size
            session.resize(Viewport(size.width, size.height))
            surface.close()
            surface = Surface.makeRasterN32Premul(size.width, size.height)
        }
        session.frame()?.use { frame ->
            surface.canvas.clear(0)
            frame.draw(surface.canvas)
            surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { data -> bitmap = ImageIO.read(ByteArrayInputStream(data.bytes)) } }
            panel.repaint()
        }
    }
    window.addWindowFocusListener(object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent) { session.setFocused(true) }
        override fun windowLostFocus(e: WindowEvent) { session.setFocused(false) }
    })
    window.addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) {
            timer.stop(); session.close(); surface.close(); window.dispose()
        }
    })
    window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    window.contentPane.add(panel)
    window.pack()
    window.setLocationRelativeTo(null)
    window.isVisible = true
    timer.start()
    panel.requestFocusInWindow()
}

private fun InputEvent.modifiers() = Modifiers(isShiftDown, isControlDown, isAltDown, isMetaDown)
private fun KeyEvent.uiKey(): UiKey = when (keyCode) {
    KeyEvent.VK_A -> UiKey.A; KeyEvent.VK_C -> UiKey.C; KeyEvent.VK_V -> UiKey.V; KeyEvent.VK_X -> UiKey.X; KeyEvent.VK_Y -> UiKey.Y; KeyEvent.VK_Z -> UiKey.Z
    KeyEvent.VK_ENTER -> UiKey.ENTER; KeyEvent.VK_ESCAPE -> UiKey.ESCAPE; KeyEvent.VK_TAB -> UiKey.TAB; KeyEvent.VK_SPACE -> UiKey.SPACE
    KeyEvent.VK_BACK_SPACE -> UiKey.BACKSPACE; KeyEvent.VK_DELETE -> UiKey.DELETE
    KeyEvent.VK_LEFT -> UiKey.LEFT; KeyEvent.VK_RIGHT -> UiKey.RIGHT; KeyEvent.VK_UP -> UiKey.UP; KeyEvent.VK_DOWN -> UiKey.DOWN
    KeyEvent.VK_HOME -> UiKey.HOME; KeyEvent.VK_END -> UiKey.END; KeyEvent.VK_PAGE_UP -> UiKey.PAGE_UP; KeyEvent.VK_PAGE_DOWN -> UiKey.PAGE_DOWN
    else -> UiKey.UNKNOWN
}
