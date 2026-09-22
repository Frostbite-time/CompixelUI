package dev.composemc.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.*
import dev.composemc.bridge.ComposeThread
import dev.composemc.host.UiSession
import dev.composemc.platform.*
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.input.OreColorPicker
import dev.composemc.ui.ore.input.OreDoubleField
import dev.composemc.ui.ore.input.OreLongField
import dev.composemc.ui.ore.navigation.OreTreeNode
import dev.composemc.ui.ore.navigation.OreTreeView
import dev.composemc.ui.ore.overlay.OreContextMenuArea
import dev.composemc.ui.ore.overlay.OreMenu
import dev.composemc.ui.ore.overlay.OreMenuItem
import dev.composemc.ui.ore.overlay.OreTooltip
import dev.composemc.ui.ore.overlay.OreWindow
import dev.composemc.ui.ore.overlay.OreWindowState
import dev.composemc.ui.ore.selection.OreCheckbox
import dev.composemc.ui.ore.selection.OreRadioButton
import dev.composemc.ui.ore.selection.OreSelect
import dev.composemc.ui.ore.selection.OreTabButton
import dev.composemc.ui.ore.theme.OreTheme
import org.junit.jupiter.api.Test
import kotlin.test.*

class OreExtendedControlsTest {
    @Test fun `packaged component exercise drives the actual F8 pages`() {
        for(page in dev.composemc.demo.oreComponentPages) {
            val model=ComposeThread.call {dev.composemc.demo.DemoModel().apply {this.page=page}}
            val exercise=dev.composemc.demo.OreComponentExercise(model,page)
            UiSession(Viewport(1280,960,2f)){dev.composemc.demo.OreDemoScreen(model) {modifier->
                dev.composemc.demo.ItemBrowserDemo(model.itemBrowser,List(256){"Item $it"},modifier) {index,iconModifier->
                    OreIcon(OreGlyph.entries[index%OreGlyph.entries.size],iconModifier)
                }
            }}.use {session->
                repeat(210){frame->val now=1_000_000_000L+frame*20_000_000L;session.frame(now)?.close();exercise.advance(session,now)}
                exercise.verify()
            }
        }
    }
    @Test fun `mouse exit is not pinned by a clicked trigger focus before tooltip lock`() {
        Fixture {b->OreTooltip(tooltip={OreText("Details",b("body"))},modifier=b("anchor"),lockDelayMillis=600) {OreButton("Hover",{})}}.use {f->
            f.click("anchor");assertTrue(f.has("body"));f.hoverAt(Offset(450f,350f));assertFalse(f.has("body"))
        }
    }
    @Test fun `long editing retains values beyond double precision and saturates signed endpoints`() {
        val number=mutableLongStateOf(9_007_199_254_740_993L)
        Fixture {b->OreLongField(number.longValue,{number.longValue=it},b("field").width(320.dp))}.use {f->
            f.click("field");f.key(UiKey.UP);assertEquals(9_007_199_254_740_994L,number.longValue)
            f.replace("9223372036854775807");f.key(UiKey.ENTER);f.key(UiKey.UP,Modifiers(control=true));assertEquals(Long.MAX_VALUE,number.longValue)
            f.replace("-9223372036854775808");f.key(UiKey.ENTER);f.key(UiKey.DOWN,Modifiers(control=true));assertEquals(Long.MIN_VALUE,number.longValue)
            f.replace("9223372036854775808");f.key(UiKey.ENTER);assertEquals(Long.MIN_VALUE,number.longValue)
            f.key(UiKey.ESCAPE);f.click("field");f.key(UiKey.UP);assertEquals(Long.MIN_VALUE+1,number.longValue)
        }
    }
    @Test fun `double editor supports partial scientific input and rejects nonfinite or out of range values`() {
        val number=mutableDoubleStateOf(.3)
        Fixture {b->Column {OreDoubleField(number.doubleValue,{number.doubleValue=it},b("field").width(320.dp),range=-2000.0..2000.0,step=.1)
            OreButton("Other",{},b("other"))}}.use {f->
            f.click("field");f.key(UiKey.UP);assertEquals(.4,number.doubleValue)
            f.replace("1e3");f.key(UiKey.ENTER);assertEquals(1000.0,number.doubleValue)
            f.replace("1e999");f.key(UiKey.ENTER);assertEquals(1000.0,number.doubleValue)
            f.click("other");f.click("field");f.replace("-")
            ComposeThread.call {number.doubleValue=100.0};f.frame()
            f.session.commitText(".5");f.frame();f.key(UiKey.ENTER);assertEquals(-.5,number.doubleValue)
            f.replace("3000");f.key(UiKey.ENTER);assertEquals(-.5,number.doubleValue)
        }
    }
    @Test fun `radio and mixed checkbox activate once and honor disabled state`() {
        val choice=mutableIntStateOf(0);val check=mutableStateOf(ToggleableState.Indeterminate)
        Fixture {b->Column {
            OreRadioButton(choice.intValue==0,{choice.intValue=0},b("a"),label="A")
            OreRadioButton(choice.intValue==1,{choice.intValue=1},b("b"),label="B")
            OreRadioButton(false,{error("Disabled radio")},b("disabled"),enabled=false,label="Disabled")
            OreCheckbox(check.value,{check.value=if(check.value==ToggleableState.On)ToggleableState.Off else ToggleableState.On},b("mixed"),label="All")
        }}.use {f->
            f.click("b");assertEquals(1,choice.intValue);f.click("disabled");assertEquals(1,choice.intValue)
            f.click("mixed");assertEquals(ToggleableState.On,check.value);f.key(UiKey.SPACE);assertEquals(ToggleableState.Off,check.value)
        }
    }
    @Test fun `joined tabs retain one selection and arrow navigation skips disabled options`() {
        val choice=mutableIntStateOf(0)
        Fixture {b->OreTabButton(listOf("Peaceful","Easy","Normal","Hard"),choice.intValue,{choice.intValue=it},b("tabs").width(400.dp),optionEnabled={it!=1})}.use {f->
            f.clickAt(Offset(50f,12f));f.key(UiKey.RIGHT);assertEquals(2,choice.intValue)
            f.key(UiKey.RIGHT);assertEquals(3,choice.intValue);f.key(UiKey.RIGHT);assertEquals(0,choice.intValue)
            f.clickAt(Offset(150f,12f));assertEquals(0,choice.intValue)
        }
    }
    @Test fun `select keyboard activation skips disabled options and escape cancels`() {
        val choice=mutableStateOf("A")
        Fixture {b->OreSelect(listOf("A","B","C"),choice.value,{choice.value=it},b("select").width(180.dp),optionEnabled={it!="B"})}.use {f->
            f.click("select");f.key(UiKey.DOWN);f.key(UiKey.ENTER);assertEquals("C",choice.value)
            f.key(UiKey.DOWN);f.key(UiKey.UP);f.key(UiKey.ESCAPE);assertEquals("C",choice.value)
            f.key(UiKey.DOWN);f.key(UiKey.UP);f.key(UiKey.ENTER);assertEquals("A",choice.value)
        }
    }
    @Test fun `context menu consumes right click and runs only the selected enabled command`() {
        var command="";var underlying=0
        Fixture {b->OreContextMenuArea(listOf(OreMenuItem("disabled","Disabled",false){command="bad"},OreMenuItem("copy","Copy"){command="copy"},OreMenuItem("delete","Delete"){command="delete"}),b("area").size(100.dp,30.dp)) {
            OreButton("Target",{underlying++},Modifier.fillMaxSize())
        }}.use {f->
            f.clickAt(f.point("area"),MouseButton.RIGHT);f.key(UiKey.DOWN);f.key(UiKey.ENTER)
            assertEquals("delete",command);assertEquals(0,underlying)
        }
    }
    @Test fun `tree keeps navigation distinct from selection and collapses descendants`() {
        val nodes=listOf(OreTreeNode("root","Root",listOf(OreTreeNode("child","Child"),OreTreeNode("off","Disabled",enabled=false))),OreTreeNode("other","Other"))
        val selected=mutableStateOf<String?>(null);val expanded=mutableStateOf(emptySet<String>())
        Fixture {b->OreTreeView(nodes,selected.value,{selected.value=it},expanded.value,{id,open->expanded.value=if(open)expanded.value+id else expanded.value-id},Modifier.size(220.dp,200.dp),nodeContent={node->OreText(node.label,b(node.id))})}.use {f->
            f.click("root");assertEquals("root",selected.value)
            f.key(UiKey.RIGHT);assertTrue(f.has("child"));f.key(UiKey.DOWN);assertEquals("root",selected.value)
            f.key(UiKey.ENTER);assertEquals("child",selected.value)
            f.key(UiKey.LEFT);f.key(UiKey.ENTER);assertEquals("root",selected.value)
            f.key(UiKey.LEFT);assertFalse(f.has("child"))
        }
    }
    @Test fun `large tree composes bounded visible rows and keyboard reaches the final item`() {
        val nodes=List(10_000){OreTreeNode("node-$it","Node $it")}
        val selected=mutableStateOf<String?>(null);var composed=0;var maximum=0
        Fixture {b->OreTreeView(nodes,selected.value,{selected.value=it},emptySet(),{_,_->},Modifier.size(220.dp,200.dp),nodeContent={node->
            DisposableEffect(node.id){composed++;maximum=maxOf(maximum,composed);onDispose {composed--}}
            OreText(node.label,b(node.id))
        })}.use {f->
            f.click("node-0");f.key(UiKey.END);f.advance(60);f.key(UiKey.ENTER)
            assertEquals("node-9999",selected.value);assertTrue(maximum<100,"Composed $maximum rows")
        }
        assertEquals(0,composed)
    }
    @Test fun `tooltip appears immediately leaves immediately before lock and retains a nested hover chain`() {
        Fixture {b->OreTooltip(tooltip={
            OreText("First layer",b("body"))
            OreTooltip(tooltip={OreText("Second layer",b("child-body"))},modifier=b("child-anchor"),lockDelayMillis=120,exitDelayMillis=100) {
                OreText("Hover details")
            }
        },modifier=b("anchor").size(100.dp,24.dp),lockDelayMillis=120,exitDelayMillis=100) {OreText("Hover me")}}.use {f->
            assertFalse(f.has("body"));f.hover("anchor");assertTrue(f.has("body"))
            f.hoverAt(Offset(450f,350f));assertFalse(f.has("body"))
            f.hover("anchor");f.advance(12)
            f.hover("child-anchor");assertTrue(f.has("body"));assertTrue(f.has("child-body"))
            f.advance(12);f.hover("child-body");f.advance(20)
            assertTrue(f.has("body"));assertTrue(f.has("child-body"))
            f.hoverAt(Offset(450f,350f));f.advance(30)
            assertFalse(f.has("body"));assertFalse(f.has("child-body"))
        }
    }
    @Test fun `tooltip closes on window blur and disabling even after lock`() {
        val enabled=mutableStateOf(true)
        Fixture {b->OreTooltip(tooltip={OreText("Details",b("body"))},modifier=b("anchor").size(90.dp,24.dp),enabled=enabled.value,lockDelayMillis=40) {OreText("Hover")}}.use {f->
            f.hover("anchor");f.advance(10);assertTrue(f.has("body"))
            f.session.setFocused(false);f.frame();assertFalse(f.has("body"))
            f.session.setFocused(true);f.hoverAt(Offset(450f,350f));f.hover("anchor");f.advance(10)
            ComposeThread.call {enabled.value=false};f.frame();assertFalse(f.has("body"))
        }
    }
    @Test fun `color hex uses RGBA and plane interaction preserves alpha`() {
        val color=mutableStateOf(Color.Red);val enabled=mutableStateOf(true)
        Fixture {b->OreColorPicker(color.value,{color.value=it},b("picker").width(220.dp),enabled.value)}.use {f->
            val rect=f.rect("picker");f.clickAt(Offset(rect.center.x+15,rect.bottom-12))
            f.replace("#11223344");f.key(UiKey.ENTER);assertEquals(0x44112233,color.value.toArgb())
            f.clickAt(Offset(55f,25f));assertEquals(0x44,color.value.toArgb() ushr 24)
            assertEquals(.75f,maxOf(color.value.red,color.value.green,color.value.blue),.02f)
            val old=color.value;ComposeThread.call {enabled.value=false};f.frame();f.clickAt(Offset(120f,70f));assertEquals(old,color.value)
        }
    }
    @Test fun `floating window drags resizes and clamps when its viewport shrinks`() {
        val state=OreWindowState(DpOffset(30.dp,40.dp),DpSize(200.dp,150.dp));var closed=false
        Fixture {_ -> OreWindow("Window",{closed=true},state){OreText("Content")}}.use {f->
            f.drag(Offset(90f,54f),Offset(140f,84f));assertEquals(80f,state.position.x.value,1f);assertEquals(70f,state.position.y.value,1f)
            f.drag(Offset(277f,217f),Offset(337f,267f));assertTrue(state.size.width>245.dp);assertTrue(state.size.height>185.dp)
            val right=state.position.x+state.size.width;val bottom=state.position.y+state.size.height
            f.drag(Offset(state.position.x.value+2,state.position.y.value+2),Offset(state.position.x.value+32,state.position.y.value+22))
            assertEquals(right,state.position.x+state.size.width);assertEquals(bottom,state.position.y+state.size.height)
            assertEquals(110f,state.position.x.value,1f);assertEquals(90f,state.position.y.value,1f)
            f.session.resize(Viewport(160,100));f.frame();f.frame()
            assertTrue(state.size.width<=160.dp&&state.size.height<=100.dp)
            assertEquals(0.dp,state.position.x);assertEquals(0.dp,state.position.y);assertFalse(closed)
        }
    }

    @Test fun `floating window blocks covered buttons but leaves the surrounding page usable`() {
        var behind=0;var inside=0
        Fixture {b->
            OreButton("Behind",{behind++},Modifier.fillMaxSize())
            OreWindow("Window",{},OreWindowState(DpOffset(30.dp,40.dp),DpSize(200.dp,150.dp))) {
                OreButton("Inside",{inside++},b("inside"))
            }
        }.use {f->
            f.clickAt(Offset(150f,165f));assertEquals(0,behind)
            f.click("inside");assertEquals(1,inside);assertEquals(0,behind)
            f.clickAt(Offset(400f,300f));assertEquals(1,behind)
        }
    }

    @Test fun `menu dismisses outside without activating its item`() {
        val open=mutableStateOf(true);var calls=0
        Fixture {_ -> Box(Modifier.size(100.dp,25.dp)) {OreMenu(open.value,{open.value=false},listOf(OreMenuItem("act","Action"){calls++}))}}.use {f->
            f.clickAt(Offset(470f,390f));assertFalse(open.value);assertEquals(0,calls)
        }
    }
    @Test fun `menu has a compact width and stays inside a near-edge viewport`() {
        Fixture {b->Box(Modifier.offset(440.dp,350.dp).size(30.dp,25.dp)) {
            OreMenu(true,{},listOf(OreMenuItem("act","Action"){}),b("menu"))
        }}.use {f->
            val rect=f.rect("menu")
            assertTrue(rect.width in 100f..220f,"Menu expanded to ${rect.width}px")
            assertTrue(rect.left>=0&&rect.right<=480&&rect.top>=0&&rect.bottom<=400,"Menu out of bounds: $rect")
        }
    }

    private class Fixture(content:@Composable (@Composable (String)->Modifier)->Unit):AutoCloseable {
        private val bounds=mutableMapOf<String,Rect>();private var time=1_000_000_000L
        val session=UiSession(Viewport(480,400)) {OreTheme {Box(Modifier.fillMaxSize()) {content {id->
            DisposableEffect(id){onDispose {bounds.remove(id)}}
            Modifier.onGloballyPositioned {bounds[id]=it.boundsInWindow()}
        }}}}
        init {frame();frame()}
        fun frame(){time+=20_000_000;session.frame(time)?.close()}
        fun advance(count:Int){repeat(count){frame()}}
        fun has(id:String)=ComposeThread.call {id in bounds}
        fun rect(id:String)=ComposeThread.call {bounds.getValue(id)}
        fun point(id:String)=rect(id).center
        fun hover(id:String)=hoverAt(point(id))
        fun hoverAt(p:Offset){session.pointer(PointerInput(PointerAction.MOVE,p.x,p.y));frame();frame()}
        fun click(id:String)=clickAt(point(id))
        fun clickAt(p:Offset,button:MouseButton=MouseButton.LEFT){hoverAt(p);session.pointer(PointerInput(PointerAction.PRESS,p.x,p.y,button));session.pointer(PointerInput(PointerAction.RELEASE,p.x,p.y,button));frame();frame()}
        fun drag(a:Offset,b:Offset){hoverAt(a);session.pointer(PointerInput(PointerAction.PRESS,a.x,a.y,MouseButton.LEFT));frame();session.pointer(PointerInput(PointerAction.MOVE,b.x,b.y));frame();session.pointer(PointerInput(PointerAction.RELEASE,b.x,b.y,MouseButton.LEFT));frame()}
        fun key(key:UiKey,modifiers:Modifiers=Modifiers()){session.key(KeyInput(key,true,modifiers));session.key(KeyInput(key,false,modifiers));frame();frame()}
        fun replace(text:String){key(UiKey.A,Modifiers(control=true));session.commitText(text);frame()}
        override fun close()=session.close()
    }
}
