@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.composemc.ui.ore.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.button.OreIconButton
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.layout.OreDivider
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.theme.OreTheme

@Stable
class OreWindowState(initialPosition:DpOffset=DpOffset(20.dp,20.dp),initialSize:DpSize=DpSize(240.dp,160.dp)) {
    var position by mutableStateOf(initialPosition)
    var size by mutableStateOf(initialSize)
}

@Composable
fun rememberOreWindowState(position:DpOffset=DpOffset(20.dp,20.dp),size:DpSize=DpSize(240.dp,160.dp)) =
    remember { OreWindowState(position,size) }

/** In-scene, non-modal floating window. Place after the page in a bounded Box to overlay it.
 * Only its frame receives pointer input; the surrounding page remains interactive.
 * The caller retains state and controls visibility and ordering of multiple windows.
 */
@Composable
fun OreWindow(title:String,onClose:()->Unit,state:OreWindowState=rememberOreWindowState(),modifier:Modifier=Modifier,
    minimumSize:DpSize=DpSize(120.dp,80.dp),closeLabel:String="Close",moveLabel:String="Move window",
    resizeLabel:String="Resize window",content:@Composable ColumnScope.()->Unit) {
    require(listOf(state.position.x,state.position.y,state.size.width,state.size.height,minimumSize.width,minimumSize.height).all {it.value.isFinite()})
    require(state.size.width>0.dp&&state.size.height>0.dp&&minimumSize.width>0.dp&&minimumSize.height>0.dp)
    val density=LocalDensity.current
    val colors=OreTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        require(constraints.hasBoundedWidth&&constraints.hasBoundedHeight) { "OreWindow needs a bounded parent Box" }
        if(maxWidth<=0.dp||maxHeight<=0.dp)return@BoxWithConstraints
        val areaWidth=maxWidth;val areaHeight=maxHeight
        val minWidth=minOf(minimumSize.width,maxWidth);val minHeight=minOf(minimumSize.height,maxHeight)
        val width=state.size.width.coerceIn(minWidth,maxWidth);val height=state.size.height.coerceIn(minHeight,maxHeight)
        val x=state.position.x.coerceIn(0.dp,maxWidth-width);val y=state.position.y.coerceIn(0.dp,maxHeight-height)
        SideEffect { if(state.size!=DpSize(width,height))state.size=DpSize(width,height);if(state.position!=DpOffset(x,y))state.position=DpOffset(x,y) }
        fun resize(edge:Int,delta:Offset) {
            val dx=with(density){delta.x.toDp()};val dy=with(density){delta.y.toDp()}
            var left=state.position.x;var top=state.position.y
            var right=left+state.size.width;var bottom=top+state.size.height
            if(edge and 1!=0)left=(left+dx).coerceIn(0.dp,right-minWidth)
            if(edge and 2!=0)right=(right+dx).coerceIn(left+minWidth,maxWidth)
            if(edge and 4!=0)top=(top+dy).coerceIn(0.dp,bottom-minHeight)
            if(edge and 8!=0)bottom=(bottom+dy).coerceIn(top+minHeight,maxHeight)
            state.position=DpOffset(left,top);state.size=DpSize(right-left,bottom-top)
        }
        Box(Modifier.offset(x,y).size(width,height)
            .onPointerEvent(PointerEventType.Press) {event->event.changes.forEach {it.consume()}}
            .onPointerEvent(PointerEventType.Scroll) {event->event.changes.forEach {it.consume()}}) {
          OreSurface(Modifier.fillMaxSize(),bottomLedge=2.dp,ledgeColor=colors.ledge) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(colors.raised),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).heightIn(min=24.dp).semantics {contentDescription=moveLabel}
                        .oreWindowDrag {delta->
                            state.position=DpOffset((state.position.x+with(density){delta.x.toDp()}).coerceIn(0.dp,areaWidth-state.size.width),
                                (state.position.y+with(density){delta.y.toDp()}).coerceIn(0.dp,areaHeight-state.size.height))
                        }.padding(horizontal=8.dp,vertical=5.dp),contentAlignment=Alignment.Center) {
                        OreText(title,textAlign=TextAlign.Center,style=OreTheme.typography.title,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    OreIconButton(OreGlyph.Cross,closeLabel,onClose,Modifier.padding(end=4.dp))
                }
                OreDivider()
                Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(7.dp),verticalArrangement=Arrangement.spacedBy(5.dp),content=content)
            }
          }
            // Thin edge handles and larger corner handles; each preserves the opposite edge.
            Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().semantics {contentDescription="$resizeLabel left"}.oreWindowDrag {resize(1,it)})
            Box(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().semantics {contentDescription="$resizeLabel right"}.oreWindowDrag {resize(2,it)})
            Box(Modifier.align(Alignment.TopCenter).height(4.dp).fillMaxWidth().semantics {contentDescription="$resizeLabel top"}.oreWindowDrag {resize(4,it)})
            Box(Modifier.align(Alignment.BottomCenter).height(4.dp).fillMaxWidth().semantics {contentDescription="$resizeLabel bottom"}.oreWindowDrag {resize(8,it)})
            listOf(Triple(Alignment.TopStart,5,"top left"),Triple(Alignment.TopEnd,6,"top right"),Triple(Alignment.BottomStart,9,"bottom left"),Triple(Alignment.BottomEnd,10,"bottom right")).forEach {(align,edge,name)->
                Box(Modifier.align(align).size(7.dp).semantics {contentDescription="$resizeLabel $name"}.oreWindowDrag {resize(edge,it)})
            }
        }
    }
}

@Composable
private fun Modifier.oreWindowDrag(onDrag:(Offset)->Unit):Modifier {
    val latest by rememberUpdatedState(onDrag)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown()
            if(!currentEvent.buttons.isPrimaryPressed)return@awaitEachGesture
            down.consume()
            do {
                val event=awaitPointerEvent()
                val change=event.changes.firstOrNull {it.id==down.id}?:break
                if(!change.pressed)break
                latest(change.positionChange());change.consume()
            }while(true)
        }
    }
}
