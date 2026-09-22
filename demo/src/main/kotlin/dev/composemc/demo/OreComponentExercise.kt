package dev.composemc.demo

import dev.composemc.bridge.ComposeThread
import dev.composemc.host.UiSession
import dev.composemc.platform.*

/** The actual F8 pages are also exercised from packaged, hidden GPU benchmark screens. */
val oreComponentPages = listOf(DemoPage.Selection,DemoPage.Menus,DemoPage.Numbers,DemoPage.Colors,DemoPage.Tree,DemoPage.Tooltips,DemoPage.Windows,
    DemoPage.Fields,DemoPage.Slots,DemoPage.Surfaces,DemoPage.Items)

class OreComponentExercise(private val model:DemoModel,private val page:DemoPage) {
    private var frame=0
    private var exercised=false
    private var tooltipStage=0
    private var tooltipSince=0L
    val complete get()=exercised
    fun advance(session:UiSession,nowNanos:Long=System.nanoTime()) {
        frame++
        fun hover(id:String) {
            val p=ComposeThread.call {checkNotNull(model.bounds[id]) {"Missing preview target $id"}.center}
            session.pointer(PointerInput(PointerAction.MOVE,p.x,p.y))
        }
        fun click(id:String,button:MouseButton=MouseButton.LEFT) {
            hover(id)
            val p=ComposeThread.call {model.bounds.getValue(id).center}
            session.pointer(PointerInput(PointerAction.PRESS,p.x,p.y,button))
            session.pointer(PointerInput(PointerAction.RELEASE,p.x,p.y,button))
        }
        fun key(key:UiKey,modifiers:Modifiers=Modifiers()) {
            session.key(KeyInput(key,true,modifiers));session.key(KeyInput(key,false,modifiers))
        }
        when(page) {
            DemoPage.Selection -> when(frame) {
                20->click("choices-select")
                40->{key(UiKey.DOWN);key(UiKey.ENTER)}
                60->{click("mixed");exercised=true}
            }
            DemoPage.Menus -> when(frame) {
                20->click("menu-area",MouseButton.RIGHT)
                40->{key(UiKey.DOWN);key(UiKey.ENTER);exercised=true}
                80->click("menu-button")
            }
            DemoPage.Numbers -> when(frame) {
                20->{click("long-field");key(UiKey.A,Modifiers(control=true));session.commitText("9223372036854775806");key(UiKey.ENTER)}
                40->{key(UiKey.UP);exercised=true}
            }
            DemoPage.Colors -> if(frame==20) {
                val bounds=ComposeThread.call {model.bounds.getValue("color-picker")}
                val x=bounds.left+bounds.width*.3f;val y=bounds.top+bounds.width*.15f
                session.pointer(PointerInput(PointerAction.MOVE,x,y));session.pointer(PointerInput(PointerAction.PRESS,x,y,MouseButton.LEFT));session.pointer(PointerInput(PointerAction.RELEASE,x,y,MouseButton.LEFT));exercised=true
            }
            DemoPage.Tree -> when(frame) {
                20->click("tree-world")
                40->key(UiKey.LEFT)
                60->{key(UiKey.RIGHT);exercised=true}
            }
            DemoPage.Tooltips -> if(frame>=10&&(tooltipStage==0||nowNanos-tooltipSince>=800_000_000L)) {
                when(tooltipStage) {
                    0->hover("tooltip-anchor")
                    1->hover("tooltip-term")
                    2->hover("tooltip-item")
                    3->{click("tooltip-action");exercised=true}
                }
                tooltipStage++;tooltipSince=nowNanos
            }
            DemoPage.Windows -> if(frame==20) {
                click("window-field");session.commitText("Window input");exercised=true
            }
            DemoPage.Fields -> when(frame) {
                20->click("field-error")
                40->{session.commitText("1");exercised=true}
            }
            DemoPage.Slots -> if(frame==20) {click("slots-next");exercised=true}
            DemoPage.Surfaces -> if(frame==20) {exercised=true}
            DemoPage.Items -> when(frame) {
                20->{
                    val p=ComposeThread.call {model.itemBrowser.visibleCells.getValue(0).center}
                    session.pointer(PointerInput(PointerAction.MOVE,p.x,p.y))
                    session.pointer(PointerInput(PointerAction.PRESS,p.x,p.y,MouseButton.LEFT))
                    session.pointer(PointerInput(PointerAction.RELEASE,p.x,p.y,MouseButton.LEFT))
                }
                40->ComposeThread.call {model.itemBrowser.scrollTarget=120}
                60->{exercised=true}
            }
            else->error("Not a component page: $page")
        }
    }
    fun verify()=ComposeThread.call {
        check(exercised) {"Component exercise did not complete: $page at frame $frame"}
        when(page) {
            DemoPage.Selection->check(model.choice==3&&model.mixedSelection.size==3)
            DemoPage.Menus->check(model.menuAction.isNotEmpty())
            DemoPage.Numbers->check(model.longValue==Long.MAX_VALUE)
            DemoPage.Colors->check(model.color!=androidx.compose.ui.graphics.Color(0xCC3C8527))
            DemoPage.Tree->check(model.treeSelection=="world"&&"world" in model.expandedTree)
            DemoPage.Tooltips->check(model.tooltipClicks==1&&"tooltip-level-3" in model.bounds)
            DemoPage.Windows->check(model.query=="Window input")
            DemoPage.Fields->check(model.scrolledText=="1")
            DemoPage.Slots->check(model.scrollPage==1&&"slots-paged" in model.bounds)
            DemoPage.Surfaces->check("surface-inset" in model.bounds&&"surface-raised" in model.bounds)
            DemoPage.Items->check(model.itemBrowser.selected==0&&model.itemBrowser.firstVisible>=120)
            else->error("Not a component page")
        }
    }
}
