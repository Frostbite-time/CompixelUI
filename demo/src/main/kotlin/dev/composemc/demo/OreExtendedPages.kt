package dev.composemc.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.display.OreGlyph
import dev.composemc.ui.ore.display.OreIcon
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.input.OreColorPicker
import dev.composemc.ui.ore.input.OreDoubleField
import dev.composemc.ui.ore.input.OreLongField
import dev.composemc.ui.ore.input.OreTextField
import dev.composemc.ui.ore.layout.OreDivider
import dev.composemc.ui.ore.layout.OreSurface
import dev.composemc.ui.ore.navigation.OreTreeNode
import dev.composemc.ui.ore.navigation.OreTreeView
import dev.composemc.ui.ore.overlay.OreContextMenuArea
import dev.composemc.ui.ore.overlay.OreMenu
import dev.composemc.ui.ore.overlay.OreMenuItem
import dev.composemc.ui.ore.overlay.OreTooltip
import dev.composemc.ui.ore.overlay.OreWindow
import dev.composemc.ui.ore.selection.OreCheckbox
import dev.composemc.ui.ore.selection.OreRadioButton
import dev.composemc.ui.ore.selection.OreSelect
import dev.composemc.ui.ore.selection.OreTabButton
import dev.composemc.ui.ore.theme.OreTheme

internal typealias DemoLabel = (String,String)->String

@Composable
internal fun Modifier.componentBounds(model:DemoModel,id:String):Modifier {
    DisposableEffect(model,id){onDispose {model.bounds.remove(id)}}
    return onGloballyPositioned {model.bounds[id]=it.boundsInWindow()}
}

@Composable
internal fun SelectionPage(model:DemoModel,label:DemoLabel) {
    val choices=listOf(label("Peaceful","和平"),label("Easy","简单"),label("Normal","一般"),label("Hard","困难"))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        OreText(label("Difficulty","难度"),style=OreTheme.typography.title)
        OreTabButton(choices,model.choice,{model.choice=it},Modifier.fillMaxWidth().componentBounds(model,"choices-tabs"))
        OreText(label("Choose one option. The selected segment stays pressed.","只能选中一项；选中项保持下沉。"),style=OreTheme.typography.caption)
        OreSelect(choices.indices.toList(),model.choice,{model.choice=it},Modifier.fillMaxWidth().componentBounds(model,"choices-select"),optionLabel={choices[it]})
        Column(Modifier.selectableGroup()) {choices.forEachIndexed {index,text->
            OreRadioButton(model.choice==index,{model.choice=index},Modifier.componentBounds(model,"radio-$index"),label=text)
        }}
        OreDivider()
        val state=when(model.mixedSelection.size){0->ToggleableState.Off;3->ToggleableState.On;else->ToggleableState.Indeterminate}
        OreCheckbox(state,{model.mixedSelection=if(state==ToggleableState.On)emptySet()else setOf(0,1,2)},Modifier.componentBounds(model,"mixed"),label=label("All categories","全部类别"))
        listOf(label("Ore","矿石"),label("Building blocks","建筑方块"),label("Tools","工具")).forEachIndexed {index,text->
            OreCheckbox(index in model.mixedSelection,{on->model.mixedSelection=if(on)model.mixedSelection+index else model.mixedSelection-index},Modifier.padding(start=15.dp),label=text)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OreRadioButton(false,{},enabled=false,label=label("Disabled","禁用"))
            OreRadioButton(true,{},enabled=false,label=label("Disabled selected","禁用选中"))
        }
    }
}

@Composable
internal fun MenusPage(model:DemoModel,label:DemoLabel) {
    var expanded by remember {mutableStateOf(false)}
    val actions=listOf(
        OreMenuItem("copy",label("Copy","复制"),shortcut="Ctrl+C"){model.menuAction=label("Copied","已复制")},
        OreMenuItem("rename",label("Rename","重命名")){model.menuAction=label("Rename selected","已选择重命名")},
        OreMenuItem("disabled",label("Unavailable","不可用"),enabled=false){},
        OreMenuItem("remove",label("Remove","删除"),destructive=true){model.menuAction=label("Removed","已删除")},
    )
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OreText(label("Action menu","操作菜单"),style=OreTheme.typography.title)
        Box {
            OreButton(label("Open menu","打开菜单"),{expanded=true},Modifier.componentBounds(model,"menu-button"),style=OreButtonStyle.Secondary)
            OreMenu(expanded,{expanded=false},actions)
        }
        OreContextMenuArea(actions,Modifier.fillMaxWidth().height(75.dp).componentBounds(model,"menu-area")) {
            OreSurface(Modifier.fillMaxSize()) {OreText(label("Right-click this area","右键点击此区域"),Modifier.padding(10.dp))}
        }
        OreText(label("Arrow keys navigate; Enter activates; Escape closes.","方向键切换，回车执行，Esc 关闭。"),style=OreTheme.typography.caption)
        OreText(model.menuAction,Modifier.componentBounds(model,"menu-result"))
    }
}

@Composable
internal fun NumbersPage(model:DemoModel,label:DemoLabel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OreText(label("Precise numeric input","精确数值输入"),style=OreTheme.typography.title)
        OreLongField(model.longValue,{model.longValue=it},Modifier.fillMaxWidth().componentBounds(model,"long-field"),label=label("Signed 64-bit integer","64 位整数"))
        OreText(model.longValue.toString(),style=OreTheme.typography.caption)
        OreDoubleField(model.doubleValue,{model.doubleValue=it},Modifier.fillMaxWidth().componentBounds(model,"double-field"),label=label("Decimal / exponent","小数／科学计数法"),step=.05,shiftStep=.5,controlStep=5.0)
        OreText(model.doubleValue.toString(),style=OreTheme.typography.caption)
        OreText(label("Enter commits; Escape cancels. Arrows and wheel step; Shift/Ctrl use larger steps.","回车提交，Esc 取消。方向键和滚轮步进，Shift/Ctrl 加大步长。"))
        OreLongField(42,{},Modifier.fillMaxWidth(),enabled=false,label=label("Disabled","禁用"))
    }
}

@Composable
internal fun ColorsPage(model:DemoModel,label:DemoLabel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OreText(label("Color and opacity","颜色与透明度"),style=OreTheme.typography.title)
        OreColorPicker(model.color,{model.color=it},Modifier.fillMaxWidth().componentBounds(model,"color-picker"),
            planeLabel=label("Saturation and brightness","饱和度与明度"),hueLabel=label("Hue","色相"),alphaLabel=label("Opacity","透明度"))
        OreText(label("Drag the plane and tracks, or enter #RRGGBBAA. Focused tracks also accept arrow keys.","拖动色板与滑条，或输入 #RRGGBBAA。滑条获得焦点后也可用方向键调整。"),style=OreTheme.typography.caption)
    }
}

@Composable
internal fun TreePage(model:DemoModel,label:DemoLabel) {
    val nodes=remember(model.locale,model.largeTree) {
        if(model.largeTree)List(10_000){OreTreeNode("entry-$it",label("Entry $it","条目 $it"))}
        else listOf(OreTreeNode("world",label("World","世界"),listOf(
            OreTreeNode("general",label("General","通用")),OreTreeNode("render",label("Rendering","渲染"),listOf(OreTreeNode("lighting",label("Lighting","光照")),OreTreeNode("particles",label("Particles","粒子")))),
            OreTreeNode("server",label("Server","服务器"),enabled=false))),OreTreeNode("packs",label("Resource packs","资源包")))
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        OreText(label("Tree navigation","树形导航"),style=OreTheme.typography.title)
        OreCheckbox(model.largeTree,{model.largeTree=it},label=label("10,000 entries","10,000 个条目"))
        OreTreeView(nodes,model.treeSelection,{model.treeSelection=it},model.expandedTree,{id,open->model.expandedTree=if(open)model.expandedTree+id else model.expandedTree-id},
            Modifier.weight(1f).fillMaxWidth().componentBounds(model,"tree"),nodeContent={node->OreText(node.label,Modifier.componentBounds(model,"tree-${node.id}"))})
        OreText(label("Selected: ","已选：")+(model.treeSelection?:"—"),style=OreTheme.typography.caption)
        OreText(label("Left/right expand and collapse. Up/down move; Enter selects.","左右键展开或折叠，上下键移动，回车选择。"),style=OreTheme.typography.caption)
    }
}

@Composable
internal fun TooltipsPage(model:DemoModel,label:DemoLabel,item:(@Composable (Modifier)->Unit)?) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OreText(label("Explore nested hints","探索多层提示"),style=OreTheme.typography.title)
        OreText(label("A hint appears immediately. Hold until the green line completes, then move into it.","提示立即出现。等待绿色进度线完成，再将鼠标移入提示。"))
        OreTooltip(tooltip={
            OreText(label("First layer","第一层提示"),Modifier.componentBounds(model,"tooltip-level-1"),style=OreTheme.typography.title)
            OreText(label("This hint stays open while you explore its contents.","鼠标在提示内部时会持续显示。"))
            OreTooltip(tooltip={
                OreText(label("Second layer","第二层提示"),Modifier.componentBounds(model,"tooltip-level-2"),style=OreTheme.typography.title)
                OreTooltip(tooltip={
                    OreText(label("Third layer","第三层提示"),Modifier.componentBounds(model,"tooltip-level-3"))
                    OreButton(label("Inspect","查看"),{model.tooltipClicks++},Modifier.componentBounds(model,"tooltip-action"))
                },modifier=Modifier.componentBounds(model,"tooltip-item")) {
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        if(item!=null)item(Modifier.size(24.dp))else OreIcon(OreGlyph.Network,Modifier.size(20.dp))
                        OreText(label("Inspect this item","查看这个物品"))
                    }
                }
            },modifier=Modifier.componentBounds(model,"tooltip-term")) {OreText(label("Hover this term for details →","悬停这个词条查看详情 →"))}
        },modifier=Modifier.componentBounds(model,"tooltip-anchor")) {OreButton(label("Hover here","悬停这里"),{},style=OreButtonStyle.Secondary)}
        OreText(label("Inspections: ","查看次数：")+model.tooltipClicks)
        OreText(label("Leaving before the lock closes immediately. After locking, a short grace period lets you cross to the next layer.","锁定前移出立即关闭；锁定后有短暂保留时间，方便移入下一层。"),style=OreTheme.typography.caption)
    }
}

@Composable
internal fun WindowsPage(model:DemoModel,label:DemoLabel) {
    Box(Modifier.fillMaxSize()) {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OreText(label("Floating window","浮动窗口"),style=OreTheme.typography.title)
            OreButton(label("Open window","打开窗口"),{model.windowOpen=true},Modifier.componentBounds(model,"window-open"),style=OreButtonStyle.Secondary)
            OreText(label("Drag its title to move. Drag any edge or corner to resize.","拖动标题移动窗口，拖动任意边缘或四角调整大小。"))
        }
        if(model.windowOpen)OreWindow(label("Preview window","预览浮窗"),{model.windowOpen=false},model.windowState,
            closeLabel=label("Close","关闭"),moveLabel=label("Move window","移动窗口"),resizeLabel=label("Resize window","缩放窗口")) {
            OreText(label("The page behind this window remains interactive.","浮窗之外的页面仍可交互。"))
            OreTextField(model.query,{model.query=it},Modifier.fillMaxWidth().componentBounds(model,"window-field"),placeholder=label("Type here","在此输入"))
        }
    }
}
