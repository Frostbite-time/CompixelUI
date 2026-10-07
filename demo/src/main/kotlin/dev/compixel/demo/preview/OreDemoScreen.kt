@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.compixel.demo.preview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.button.OreIconButton
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreIcon
import dev.compixel.ui.ore.display.OreProgressBar
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.input.OreColorPicker
import dev.compixel.ui.ore.input.OreDoubleField
import dev.compixel.ui.ore.input.OreIntField
import dev.compixel.ui.ore.input.OreLongField
import dev.compixel.ui.ore.input.OreSlider
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.inventory.OreSlot
import dev.compixel.ui.ore.layout.OreDivider
import dev.compixel.ui.ore.layout.OreScreen
import dev.compixel.ui.ore.layout.OreSurface
import dev.compixel.ui.ore.layout.OreSurfaceStyle
import dev.compixel.ui.ore.navigation.OreListItem
import dev.compixel.ui.ore.navigation.OreTab
import dev.compixel.ui.ore.navigation.OreTreeNode
import dev.compixel.ui.ore.navigation.OreTreeView
import dev.compixel.ui.ore.overlay.OreContextMenuArea
import dev.compixel.ui.ore.overlay.OreDialog
import dev.compixel.ui.ore.overlay.OreMenu
import dev.compixel.ui.ore.overlay.OreMenuItem
import dev.compixel.ui.ore.overlay.OreTooltip
import dev.compixel.ui.ore.overlay.OreTooltipMode
import dev.compixel.ui.ore.overlay.OreWindow
import dev.compixel.ui.ore.scroll.OreScrollTrack
import dev.compixel.ui.ore.scroll.OreScrollbar
import dev.compixel.ui.ore.selection.OreCheckbox
import dev.compixel.ui.ore.selection.OreRadioButton
import dev.compixel.ui.ore.selection.OreSelect
import dev.compixel.ui.ore.selection.OreSwitch
import dev.compixel.ui.ore.selection.OreTabButton
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme

/** Default preview exercises the same Ore components shipped to consumer mods. */
@Composable
fun OreDemoScreen(
    model: DemoModel,
    tooltipItem: (@Composable (Modifier) -> Unit)? = null,
    slotItem: (@Composable (Int, Modifier) -> Unit)? = null,
    nativeContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val zh = model.locale == "zh_cn"
    fun label(english: String, chinese: String) = if (zh) chinese else english
    OreTheme(OreColors.scheme(model.scheme)) {
        OreScreen(
            "CompixelUI",
            maxWidth = 560.dp,
            maxHeight = 420.dp,
            footer = {
                OreButton(
                    if (zh) "EN" else "中文",
                    { model.locale = if (zh) "en_us" else "zh_cn" },
                    style = OreButtonStyle.Secondary,
                )
                Spacer(Modifier.weight(1f))
                // Cycles through the built-in schemes; the label names the next one.
                val next =
                    when (model.scheme) {
                        "default" -> "light"
                        "light" -> "twilight"
                        else -> "default"
                    }
                OreButton(
                    when (next) {
                        "light" -> label("Light", "浅色")
                        "twilight" -> label("Twilight", "暮光")
                        else -> label("Dark", "深色")
                    },
                    { model.scheme = next },
                    style = OreButtonStyle.Secondary,
                )
                OreButton(label("Apply changes", "应用更改"), { model.dialog = true }, Modifier.demoBounds(model, "apply"))
            },
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val navigationScroll = rememberScrollState()
                Box(Modifier.width(96.dp).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().padding(end = 7.dp).verticalScroll(navigationScroll),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DemoNavEntry(model, DemoPage.Settings, label("Settings", "设置"))
                        DemoNavEntry(model, DemoPage.Catalog, label("Catalog", "目录"))
                        DemoNavEntry(model, DemoPage.Buttons, label("Buttons", "按钮"))
                        DemoNavEntry(model, DemoPage.Icons, label("Icons", "图标"))
                        DemoNavEntry(model, DemoPage.Fields, label("Fields", "输入"))
                        DemoNavEntry(model, DemoPage.Toggles, label("Toggles", "开关"))
                        DemoNavEntry(model, DemoPage.Sliders, label("Sliders", "滑杆"))
                        DemoNavEntry(model, DemoPage.Lists, label("Lists", "列表"))
                        DemoNavEntry(model, DemoPage.Slots, label("Slots", "槽位"))
                        DemoNavEntry(model, DemoPage.Surfaces, label("Panels", "面板"))
                        DemoNavEntry(model, DemoPage.Selection, label("Choices", "选择控件"))
                        DemoNavEntry(model, DemoPage.Menus, label("Menus", "右键菜单"))
                        DemoNavEntry(model, DemoPage.Numbers, label("Numbers", "数值输入"))
                        DemoNavEntry(model, DemoPage.Colors, label("Colors", "颜色选择"))
                        DemoNavEntry(model, DemoPage.Tree, label("Tree", "树形列表"))
                        DemoNavEntry(model, DemoPage.Tooltips, label("Tooltips", "多层提示"))
                        DemoNavEntry(model, DemoPage.Windows, label("Windows", "浮动窗口"))
                        if (nativeContent != null) DemoNavEntry(model, DemoPage.Items, label("Items", "物品"))
                    }
                    OreScrollbar(navigationScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (model.page) {
                        DemoPage.Settings -> SettingsPage(model, ::label)
                        DemoPage.Catalog -> CatalogPage(model, ::label)
                        DemoPage.Buttons -> ButtonsPage(::label)
                        DemoPage.Icons -> IconsPage(::label)
                        DemoPage.Fields -> FieldsPage(model, ::label)
                        DemoPage.Toggles -> TogglesPage(model, ::label)
                        DemoPage.Sliders -> SlidersPage(model, ::label)
                        DemoPage.Lists -> ListsPage(model, ::label)
                        DemoPage.Slots -> SlotsPage(model, ::label, slotItem)
                        DemoPage.Surfaces -> SurfacesPage(model, ::label)
                        DemoPage.Selection -> SelectionPage(model, ::label)
                        DemoPage.Menus -> MenusPage(model, ::label)
                        DemoPage.Numbers -> NumbersPage(model, ::label)
                        DemoPage.Colors -> ColorsPage(model, ::label)
                        DemoPage.Tree -> TreePage(model, ::label)
                        DemoPage.Tooltips -> TooltipsPage(model, ::label, tooltipItem)
                        DemoPage.Windows -> WindowsPage(model, ::label)
                        DemoPage.Items ->
                            if (nativeContent != null) nativeContent(Modifier.fillMaxSize()) else ButtonsPage(::label)
                    }
                }
            }
        }
        if (model.dialog)
            OreDialog(
                label("Apply world settings?", "应用世界设置？"),
                { model.dialog = false },
                closeLabel = label("Close", "关闭"),
                buttons = {
                    OreButton(label("Apply", "应用"), { model.dialog = false }, Modifier.fillMaxWidth())
                    OreButton(
                        label("Cancel", "取消"),
                        { model.dialog = false },
                        Modifier.fillMaxWidth(),
                        style = OreButtonStyle.Secondary,
                    )
                },
            ) {
                OreText(label("Your changes apply to this preview session.", "更改将应用于本次预览会话。"))
            }
    }
}

@Composable
private fun DemoNavEntry(model: DemoModel, page: DemoPage, text: String) {
    OreTab(text, model.page == page, { model.page = page }, Modifier.fillMaxWidth())
}

@Composable
private fun SettingsPage(model: DemoModel, label: (String, String) -> String) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OreText(label("World settings", "世界设置"), style = OreTheme.typography.title) }
        item {
            OreTextField(
                model.query,
                { model.query = it },
                label = label("Profile name", "配置名称"),
                placeholder = label("Enter a name", "输入名称"),
                modifier = Modifier.fillMaxWidth().demoBounds(model, "profile"),
            )
        }
        item {
            OreSurface(Modifier.fillMaxWidth(), color = OreTheme.colors[OreColors.raised]) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OreText(label("Ambient audio", "环境音效"), Modifier.weight(1f))
                    OreSwitch(model.enabled, { model.enabled = it })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                OreText(label("Volume", "音量"), Modifier.weight(1f))
                OreText("${(model.volume * 100).toInt()}%", color = OreTheme.colors[OreColors.mutedText])
            }
            OreSlider(model.volume, { model.volume = it }, enabled = model.enabled)
            val animated by animateFloatAsState(if (model.enabled) model.volume else 0f)
            OreProgressBar(animated)
        }
        item { OreCheckbox(model.enabled, { model.enabled = it }, label = label("Enable preview effects", "启用预览效果")) }
    }
}

@Composable
private fun CatalogPage(model: DemoModel, label: (String, String) -> String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OreTextField(
            model.query,
            { model.query = it },
            placeholder = label("Search entries", "搜索条目"),
            leadingIcon = OreGlyph.MagnifyingGlass,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000, 10000, 100000).forEach { count ->
                OreTab("${count / 1000}k", model.count == count, { model.count = count }, Modifier.weight(1f))
            }
        }
        val indices =
            remember(model.query, model.count) {
                if (model.query.isBlank()) (0 until model.count).toList()
                else (0 until model.count).filter { "Entry $it".contains(model.query, ignoreCase = true) }
            }
        when {
            model.fixture == Fixture.LOADING -> OreText(label("Loading…", "加载中…"), Modifier.weight(1f))
            model.fixture == Fixture.ERROR -> {
                OreText(label("Unable to load entries", "无法加载条目"))
                OreButton(label("Retry", "重试"), { model.fixture = Fixture.READY })
            }
            model.fixture == Fixture.EMPTY || indices.isEmpty() ->
                OreText(label("No entries found", "没有找到条目"), Modifier.weight(1f))
            else -> {
                OreText("${indices.size} " + label("entries", "个条目"), color = OreTheme.colors[OreColors.mutedText])
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().demoBounds(model, "list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(indices, key = { it }) { index ->
                        OreListItem(
                            model.selected == index,
                            { model.selected = index },
                            Modifier.fillMaxWidth().demoBounds(model, "entry:$index"),
                        ) {
                            OreIcon(DemoNetworkArt)
                            OreText("Entry ${index.toString().padStart(5, '0')}", Modifier.weight(1f))
                            OreText("${(index * 37L % 4096) + 1}", color = OreTheme.colors[OreColors.mutedText])
                        }
                    }
                }
            }
        }
        OreText(label("Selected entry: ", "已选择条目：") + model.selected, style = OreTheme.typography.caption)
    }
}

@Composable
private fun ButtonsPage(label: (String, String) -> String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OreText(label("Buttons", "按钮"), style = OreTheme.typography.title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OreButton(label("Primary", "主要"), {}, Modifier.weight(1f))
            OreButton(label("Secondary", "次要"), {}, Modifier.weight(1f), style = OreButtonStyle.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OreButton(label("Remove", "删除"), {}, Modifier.weight(1f), style = OreButtonStyle.Destructive)
            OreButton(label("Quiet", "安静"), {}, Modifier.weight(1f), style = OreButtonStyle.Quiet)
        }
        OreButton(label("Disabled", "禁用"), {}, Modifier.fillMaxWidth(), enabled = false)
        OreText(label("Icons", "图标"), style = OreTheme.typography.title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (glyph in listOf(OreGlyph.Plus, OreGlyph.Pencil, OreGlyph.Trash, OreGlyph.Gear)) OreIconButton(
                glyph,
                glyph.name,
                {},
            )
        }
        OreText(label("Custom icons", "自定义图标"), style = OreTheme.typography.title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((style, enabled) in
                listOf(
                    OreButtonStyle.Primary to true,
                    OreButtonStyle.Secondary to true,
                    OreButtonStyle.Quiet to true,
                    OreButtonStyle.Secondary to false,
                )) {
                OreIconButton(label("Add", "添加"), {}, enabled = enabled, style = style) { contentColor ->
                    Canvas(Modifier.size(8.dp)) {
                        drawRect(contentColor, Offset(size.width * .375f, 0f), Size(size.width * .25f, size.height))
                        drawRect(contentColor, Offset(0f, size.height * .375f), Size(size.width, size.height * .25f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldsPage(model: DemoModel, label: (String, String) -> String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OreText(label("Text fields", "文本框"), style = OreTheme.typography.title)
        OreTextField(
            model.scrolledText,
            { model.scrolledText = it },
            label = label("Search", "搜索"),
            placeholder = label("Type to filter…", "输入过滤…"),
            leadingIcon = OreGlyph.MagnifyingGlass,
        )
        OreTextField(
            model.scrolledText,
            { model.scrolledText = it },
            placeholder = label("Read only", "只读"),
            readOnly = true,
        )
        OreTextField(
            model.scrolledText,
            { model.scrolledText = it },
            modifier = Modifier.componentBounds(model, "field-error"),
            placeholder = label("Error state", "错误状态"),
            isError = true,
        )
        OreIntField(
            model.intValue,
            { model.intValue = it },
            range = 0..100,
            label = label("Count (0–100)", "数量（0–100）"),
        )
        OreIntField(model.intUnbounded, { model.intUnbounded = it }, label = label("Unbounded", "无界"))
    }
}

@Composable
private fun TogglesPage(model: DemoModel, label: (String, String) -> String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OreText(label("Toggles", "开关"), style = OreTheme.typography.title)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OreCheckbox(model.checked, { model.checked = it }, label = label("Enabled", "启用"))
            OreSwitch(model.switched, { model.switched = it })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OreCheckbox(false, {}, enabled = false, label = label("Disabled", "禁用"))
            OreSwitch(false, {}, enabled = false)
        }
    }
}

@Composable
private fun SlidersPage(model: DemoModel, label: (String, String) -> String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OreText(label("Slider and progress", "滑块与进度"), style = OreTheme.typography.title)
        OreText("${(model.slider * 100).toInt()}%")
        OreSlider(model.slider, { model.slider = it })
        OreSlider(model.slider, { model.slider = it }, enabled = false)
        OreProgressBar(model.slider)
    }
}

@Composable
private fun ListsPage(model: DemoModel, label: (String, String) -> String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OreText(label("List rows", "列表行"), style = OreTheme.typography.title)
        val list = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(end = 10.dp),
                state = list,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(20, key = { it }) { index ->
                    OreListItem(model.selected == index, { model.selected = index }, Modifier.fillMaxWidth()) {
                        if (index % 2 == 0) OreIcon(DemoNetworkArt) else OreIcon(OreGlyph.Gear)
                        Column(Modifier.weight(1f)) {
                            OreText(label("Row ${index + 1}", "第 ${index + 1} 行"))
                            OreText(
                                label("Subtitle", "副标题"),
                                style = OreTheme.typography.caption,
                                color = OreTheme.colors[OreColors.mutedText],
                            )
                        }
                        OreIcon(OreGlyph.ArrowRight)
                    }
                }
            }
            OreScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        OreTooltip(label("Hover for a hint", "悬停查看提示"), mode = OreTooltipMode.Immediate) {
            OreButton(label("Hover me", "悬停我"), {}, style = OreButtonStyle.Secondary)
        }
    }
}

@Composable
private fun SlotsPage(
    model: DemoModel,
    label: (String, String) -> String,
    item: (@Composable (Int, Modifier) -> Unit)?,
) {
    val slotSize = 18.dp
    val maximum = 9
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val groupsPerRow = if (maxWidth >= 280.dp) 2 else 1
        val groupWidth = (maxWidth - 12.dp * (groupsPerRow - 1)) / groupsPerRow
        val columns = (groupWidth / slotSize).toInt().coerceIn(4, 9)
        val groups =
            listOf(
                Triple(label("Normal slots", "普通槽位组"), false, false),
                Triple(label("Marked slots", "标记槽位组"), true, false),
                Triple(label("Hover state", "悬停状态组"), false, true),
                Triple(label("Marked + hover", "标记与悬停组"), true, true),
            )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OreText(label("Slot groups", "槽位组"), style = OreTheme.typography.title)
            groups.chunked(groupsPerRow).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { groupIndex, (title, marked, highlight) ->
                        var hovered by remember { mutableStateOf<Int?>(null) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OreText(title, style = OreTheme.typography.caption)
                            Column {
                                repeat(3) { y ->
                                    Row {
                                        repeat(columns) { x ->
                                            val index = y * columns + x
                                            OreSlot(
                                                Modifier.size(slotSize)
                                                    .onPointerEvent(PointerEventType.Enter) { hovered = index }
                                                    .onPointerEvent(PointerEventType.Exit) {
                                                        if (hovered == index) hovered = null
                                                    },
                                                marked = marked,
                                                highlighted = index == (hovered ?: if (highlight) columns + 2 else -1),
                                            ) {
                                                if (index % 5 == 0) {
                                                    if (item != null)
                                                        item(
                                                            (rowIndex * groupsPerRow + groupIndex) * 3 * columns +
                                                                index,
                                                            Modifier.size(14.dp),
                                                        )
                                                    else
                                                        OreIcon(
                                                            OreGlyph.entries[index % OreGlyph.entries.size],
                                                            Modifier.size(10.dp),
                                                        )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OreDivider()
            OreText(label("Paged slots", "分页槽位"), style = OreTheme.typography.title)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OreText(
                    label("Page", "页") + " ${model.scrollPage + 1}/${maximum + 1}",
                    style = OreTheme.typography.caption,
                )
                OreButton("−", { model.scrollPage-- }, enabled = model.scrollPage > 0, style = OreButtonStyle.Secondary)
                OreButton(
                    "+",
                    { model.scrollPage++ },
                    Modifier.componentBounds(model, "slots-next"),
                    enabled = model.scrollPage < maximum,
                    style = OreButtonStyle.Secondary,
                )
            }
            Row(
                Modifier.height(slotSize * model.scrollRows).componentBounds(model, "slots-paged"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column {
                    repeat(model.scrollRows) { y ->
                        Row {
                            repeat(9) { x ->
                                val index = model.scrollPage * model.scrollRows * 9 + y * 9 + x
                                OreSlot(Modifier.size(slotSize), marked = index % 7 == 0) {
                                    if (index % 4 == 0)
                                        OreText((index + 1).toString(), style = OreTheme.typography.caption)
                                }
                            }
                        }
                    }
                }
                OreScrollTrack(
                    model.scrollPage,
                    maximum,
                    1f / (maximum + 1),
                    { model.scrollPage = it },
                    Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun SurfacesPage(model: DemoModel, label: (String, String) -> String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OreText(label("Surfaces and scrolling", "表面与滚动"), style = OreTheme.typography.title)
        OreSurface(Modifier.fillMaxWidth().componentBounds(model, "surface-inset"), style = OreSurfaceStyle.Inset) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OreText(label("Inset panel", "内嵌面板"))
                OreText(
                    label("Recessed content area", "下沉的内容区域"),
                    style = OreTheme.typography.caption,
                    color = OreTheme.colors[OreColors.mutedText],
                )
            }
        }
        OreSurface(
            Modifier.fillMaxWidth().componentBounds(model, "surface-raised"),
            style = OreSurfaceStyle.Raised,
            bottomLedge = 2.dp,
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OreText(label("Raised panel", "凸起面板"))
                OreText(
                    label("Elevated content area", "抬起的内容区域"),
                    style = OreTheme.typography.caption,
                    color = OreTheme.colors[OreColors.mutedText],
                )
            }
        }
        OreDivider()
        val scroll = rememberScrollState()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OreText(
                    label("Scrollable text", "可滚文本"),
                    style = OreTheme.typography.caption,
                    color = OreTheme.colors[OreColors.mutedText],
                )
                repeat(12) { OreText(label("Line ${it + 1}", "第 ${it + 1} 行")) }
            }
            OreScrollbar(scroll, Modifier.fillMaxHeight())
        }
    }
}

@Composable
private fun SelectionPage(model: DemoModel, label: (String, String) -> String) {
    val choices = listOf(label("Peaceful", "和平"), label("Easy", "简单"), label("Normal", "一般"), label("Hard", "困难"))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        OreText(label("Difficulty", "难度"), style = OreTheme.typography.title)
        OreTabButton(
            choices,
            model.choice,
            { model.choice = it },
            Modifier.fillMaxWidth().componentBounds(model, "choices-tabs"),
        )
        OreText(
            label("Choose one option. The selected segment stays pressed.", "只能选中一项；选中项保持下沉。"),
            style = OreTheme.typography.caption,
        )
        OreSelect(
            choices.indices.toList(),
            model.choice,
            { model.choice = it },
            Modifier.fillMaxWidth().componentBounds(model, "choices-select"),
            optionLabel = { choices[it] },
        )
        Column(Modifier.selectableGroup()) {
            choices.forEachIndexed { index, text ->
                OreRadioButton(
                    model.choice == index,
                    { model.choice = index },
                    Modifier.componentBounds(model, "radio-$index"),
                    label = text,
                )
            }
        }
        OreDivider()
        val state =
            when (model.mixedSelection.size) {
                0 -> ToggleableState.Off
                3 -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        OreCheckbox(
            state,
            { model.mixedSelection = if (state == ToggleableState.On) emptySet() else setOf(0, 1, 2) },
            Modifier.componentBounds(model, "mixed"),
            label = label("All categories", "全部类别"),
        )
        listOf(label("Ore", "矿石"), label("Building blocks", "建筑方块"), label("Tools", "工具")).forEachIndexed { index, text
            ->
            OreCheckbox(
                index in model.mixedSelection,
                { on -> model.mixedSelection = if (on) model.mixedSelection + index else model.mixedSelection - index },
                Modifier.padding(start = 15.dp),
                label = text,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OreRadioButton(false, {}, enabled = false, label = label("Disabled", "禁用"))
            OreRadioButton(true, {}, enabled = false, label = label("Disabled selected", "禁用选中"))
        }
    }
}

@Composable
private fun MenusPage(model: DemoModel, label: (String, String) -> String) {
    var expanded by remember { mutableStateOf(false) }
    val actions =
        listOf(
            OreMenuItem("copy", label("Copy", "复制"), shortcut = "Ctrl+C") { model.menuAction = label("Copied", "已复制") },
            OreMenuItem("rename", label("Rename", "重命名")) { model.menuAction = label("Rename selected", "已选择重命名") },
            OreMenuItem("disabled", label("Unavailable", "不可用"), enabled = false) {},
            OreMenuItem("remove", label("Remove", "删除"), destructive = true) {
                model.menuAction = label("Removed", "已删除")
            },
        )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OreText(label("Action menu", "操作菜单"), style = OreTheme.typography.title)
        Box {
            OreButton(
                label("Open menu", "打开菜单"),
                { expanded = true },
                Modifier.componentBounds(model, "menu-button"),
                style = OreButtonStyle.Secondary,
            )
            OreMenu(expanded, { expanded = false }, actions)
        }
        OreContextMenuArea(actions, Modifier.fillMaxWidth().height(75.dp).componentBounds(model, "menu-area")) {
            OreSurface(Modifier.fillMaxSize()) {
                OreText(label("Right-click this area", "右键点击此区域"), Modifier.padding(10.dp))
            }
        }
        OreText(
            label("Arrow keys navigate; Enter activates; Escape closes.", "方向键切换，回车执行，Esc 关闭。"),
            style = OreTheme.typography.caption,
        )
        OreText(model.menuAction, Modifier.componentBounds(model, "menu-result"))
    }
}

@Composable
private fun NumbersPage(model: DemoModel, label: (String, String) -> String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OreText(label("Precise numeric input", "精确数值输入"), style = OreTheme.typography.title)
        OreLongField(
            model.longValue,
            { model.longValue = it },
            Modifier.fillMaxWidth().componentBounds(model, "long-field"),
            label = label("Signed 64-bit integer", "64 位整数"),
        )
        OreText(model.longValue.toString(), style = OreTheme.typography.caption)
        OreDoubleField(
            model.doubleValue,
            { model.doubleValue = it },
            Modifier.fillMaxWidth().componentBounds(model, "double-field"),
            label = label("Decimal / exponent", "小数／科学计数法"),
            step = .05,
            shiftStep = .5,
            controlStep = 5.0,
        )
        OreText(model.doubleValue.toString(), style = OreTheme.typography.caption)
        OreText(
            label(
                "Enter commits; Escape cancels. Arrows and wheel step; Shift/Ctrl use larger steps.",
                "回车提交，Esc 取消。方向键和滚轮步进，Shift/Ctrl 加大步长。",
            )
        )
        OreLongField(42, {}, Modifier.fillMaxWidth(), enabled = false, label = label("Disabled", "禁用"))
    }
}

@Composable
private fun ColorsPage(model: DemoModel, label: (String, String) -> String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OreText(label("Color and opacity", "颜色与透明度"), style = OreTheme.typography.title)
        OreColorPicker(
            model.color,
            { model.color = it },
            Modifier.fillMaxWidth().componentBounds(model, "color-picker"),
            planeLabel = label("Saturation and brightness", "饱和度与明度"),
            hueLabel = label("Hue", "色相"),
            alphaLabel = label("Opacity", "透明度"),
        )
        OreText(
            label(
                "Drag the plane and tracks, or enter #RRGGBBAA. Focused tracks also accept arrow keys.",
                "拖动色板与滑条，或输入 #RRGGBBAA。滑条获得焦点后也可用方向键调整。",
            ),
            style = OreTheme.typography.caption,
        )
    }
}

@Composable
private fun TreePage(model: DemoModel, label: (String, String) -> String) {
    val nodes =
        remember(model.locale, model.largeTree) {
            if (model.largeTree) List(10_000) { OreTreeNode("entry-$it", label("Entry $it", "条目 $it")) }
            else
                listOf(
                    OreTreeNode(
                        "world",
                        label("World", "世界"),
                        listOf(
                            OreTreeNode("general", label("General", "通用")),
                            OreTreeNode(
                                "render",
                                label("Rendering", "渲染"),
                                listOf(
                                    OreTreeNode("lighting", label("Lighting", "光照")),
                                    OreTreeNode("particles", label("Particles", "粒子")),
                                ),
                            ),
                            OreTreeNode("server", label("Server", "服务器"), enabled = false),
                        ),
                    ),
                    OreTreeNode("packs", label("Resource packs", "资源包")),
                )
        }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OreText(label("Tree navigation", "树形导航"), style = OreTheme.typography.title)
        OreCheckbox(model.largeTree, { model.largeTree = it }, label = label("10,000 entries", "10,000 个条目"))
        OreTreeView(
            nodes,
            model.treeSelection,
            { model.treeSelection = it },
            model.expandedTree,
            { id, open -> model.expandedTree = if (open) model.expandedTree + id else model.expandedTree - id },
            Modifier.weight(1f).fillMaxWidth().componentBounds(model, "tree"),
            nodeContent = { node -> OreText(node.label, Modifier.componentBounds(model, "tree-${node.id}")) },
        )
        OreText(label("Selected: ", "已选：") + (model.treeSelection ?: "—"), style = OreTheme.typography.caption)
        OreText(
            label("Left/right expand and collapse. Up/down move; Enter selects.", "左右键展开或折叠，上下键移动，回车选择。"),
            style = OreTheme.typography.caption,
        )
    }
}

@Composable
private fun TooltipsPage(model: DemoModel, label: (String, String) -> String, item: (@Composable (Modifier) -> Unit)?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OreText(label("Immediate and nested hints", "立即提示与多层提示"), style = OreTheme.typography.title)
        OreTooltip(
            label("This hint closes as soon as you leave the button.", "离开按钮时，这条提示立即关闭。"),
            mode = OreTooltipMode.Immediate,
        ) {
            OreButton(label("Immediate hint", "立即提示"), {}, style = OreButtonStyle.Secondary)
        }
        OreText(
            label(
                "A delayed hint locks when the green line completes, then you can move into it.",
                "延迟提示在绿色进度线完成后锁定，随后可以将鼠标移入提示。",
            )
        )
        OreTooltip(
            tooltip = {
                OreText(
                    label("First layer", "第一层提示"),
                    Modifier.componentBounds(model, "tooltip-level-1"),
                    style = OreTheme.typography.title,
                )
                OreText(label("This hint stays open while you explore its contents.", "鼠标在提示内部时会持续显示。"))
                OreTooltip(
                    tooltip = {
                        OreText(
                            label("Second layer", "第二层提示"),
                            Modifier.componentBounds(model, "tooltip-level-2"),
                            style = OreTheme.typography.title,
                        )
                        OreTooltip(
                            tooltip = {
                                OreText(
                                    label("Third layer", "第三层提示"),
                                    Modifier.componentBounds(model, "tooltip-level-3"),
                                )
                                OreButton(
                                    label("Inspect", "查看"),
                                    { model.tooltipClicks++ },
                                    Modifier.componentBounds(model, "tooltip-action"),
                                )
                            },
                            modifier = Modifier.componentBounds(model, "tooltip-item"),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (item != null) item(Modifier.size(24.dp))
                                else OreIcon(DemoNetworkArt, Modifier.size(20.dp))
                                OreText(label("Inspect this item", "查看这个物品"))
                            }
                        }
                    },
                    modifier = Modifier.componentBounds(model, "tooltip-term"),
                ) {
                    OreText(label("Hover this term for details →", "悬停这个词条查看详情 →"))
                }
            },
            modifier = Modifier.componentBounds(model, "tooltip-anchor"),
        ) {
            OreButton(label("Hover here", "悬停这里"), {}, style = OreButtonStyle.Secondary)
        }
        OreText(label("Inspections: ", "查看次数：") + model.tooltipClicks)
        OreText(
            label(
                "Leaving before the lock closes immediately. After locking, a short grace period lets you cross to the next layer.",
                "锁定前移出立即关闭；锁定后有短暂保留时间，方便移入下一层。",
            ),
            style = OreTheme.typography.caption,
        )
    }
}

@Composable
private fun WindowsPage(model: DemoModel, label: (String, String) -> String) {
    Box(Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OreText(label("Floating window", "浮动窗口"), style = OreTheme.typography.title)
            OreButton(
                label("Open window", "打开窗口"),
                { model.windowOpen = true },
                Modifier.componentBounds(model, "window-open"),
                style = OreButtonStyle.Secondary,
            )
            OreText(label("Drag its title to move. Drag any edge or corner to resize.", "拖动标题移动窗口，拖动任意边缘或四角调整大小。"))
        }
        if (model.windowOpen)
            OreWindow(
                label("Preview window", "预览浮窗"),
                { model.windowOpen = false },
                model.windowState,
                closeLabel = label("Close", "关闭"),
                moveLabel = label("Move window", "移动窗口"),
                resizeLabel = label("Resize window", "缩放窗口"),
            ) {
                OreText(label("The page behind this window remains interactive.", "浮窗之外的页面仍可交互。"))
                OreTextField(
                    model.query,
                    { model.query = it },
                    Modifier.fillMaxWidth().componentBounds(model, "window-field"),
                    placeholder = label("Type here", "在此输入"),
                )
            }
    }
}

@Composable
private fun Modifier.componentBounds(model: DemoModel, id: String): Modifier {
    DisposableEffect(model, id) { onDispose { model.bounds.remove(id) } }
    return onGloballyPositioned { model.bounds[id] = it.boundsInWindow() }
}

private fun Modifier.demoBounds(model: DemoModel, key: String): Modifier = onGloballyPositioned {
    // Only keep fixed controls and the one row used by the input probe, not a dataset-sized map.
    if (!key.startsWith("entry:") || key == "entry:1") model.bounds[key] = it.boundsInRoot()
}
