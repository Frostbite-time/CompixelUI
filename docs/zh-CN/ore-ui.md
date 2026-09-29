# Ore UI

[English](../en/ore-ui.md) · [全部指南](../README.md)

Ore UI 是 CompixelUI 的 Minecraft 风格组件集：像素字体、立体按钮、内嵌输入框和深色石质面板。CompixelUI 的界面会自动套用它的主题，标准的 Compose 布局、状态和修饰符照常使用。

和其他 Compose 组件一样，控件接收值和回调，状态由你的代码持有。

## 组件一览

下列包都位于 `dev.compixel.ui.ore` 之下。

| 包 | 组件 |
| --- | --- |
| `layout` | `OreScreen`、`OrePanel`、`OreSurface`、`OreDivider` |
| `display` | `OreText`、`OreIcon`、`OreGlyph`、`OreProgressBar` |
| `button` | `OreButton`、`OreIconButton` |
| `input` | `OreTextField`、`OreIntField`、`OreLongField`、`OreDoubleField`、`OreSlider`、`OreColorPicker` |
| `selection` | `OreCheckbox`、`OreSwitch`、`OreRadioButton`、`OreTabButton`、`OreSelect` |
| `navigation` | `OreTab`、`OreListItem`、`OreTreeView` |
| `scroll` | `OreScrollbar`、`OreScrollTrack` |
| `overlay` | `OreMenu`、`OreContextMenuArea`、`OreTooltip`、`OreDialog`、`OreWindow` |
| `inventory` | `OreSlot` |
| `theme` | `OreTheme`、`OreColors`、`OreTypography` |

## 屏幕与标题栏

`OreScreen` 提供居中、尺寸限制和背景蒙层，内部用 `OrePanel` 绘制面板。两者都可以通过 `showTitleBar = false` 隐藏内置标题栏：

```kotlin
OreScreen(
    title = "仓储",
    showTitleBar = false,
    footer = { OreButton("关闭", onClick = onClose) },
) {
    // 自定义标题、工具栏或其他内容
    OreText("仓储内容")
}
```

`showTitleBar` 默认为 `true`。设为 `false` 时，标题、内置关闭按钮以及标题栏下方的分隔条都不绘制，也不保留占位高度，内容区会使用腾出的空间。面板边框、内容内边距和 footer 仍然保留；需要关闭按钮时，可像上例一样放在 footer 或自定义工具栏中。只传空字符串标题仍会显示标题栏。

## 按钮与输入

![包含文本框、滑块、复选框、开关和按钮的设置表单](../assets/ore-controls-zh-CN.png)

```kotlin
@Composable
fun ControlsExample() {
    var name by remember { mutableStateOf("Steve") }
    var volume by remember { mutableFloatStateOf(0.7f) }
    var subtitles by remember { mutableStateOf(true) }
    var music by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OreTextField(name, { name = it }, label = "玩家名称")
        OreText("音量")
        OreSlider(volume, { volume = it })
        OreCheckbox(subtitles, { subtitles = it }, label = "显示字幕")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OreSwitch(music, { music = it })
            OreText("音乐")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OreButton("保存", onClick = {})
            OreButton("取消", onClick = {}, style = OreButtonStyle.Secondary)
            OreIconButton(OreGlyph.Trash, "删除", onClick = {}, style = OreButtonStyle.Destructive)
        }
    }
}
```

- 按钮样式有 `Primary`、`Secondary`、`Destructive` 和 `Quiet`。
- `OreIntField`、`OreLongField` 和 `OreDoubleField` 把数值限制在范围内，可用方向键和鼠标滚轮步进，按住 Shift 或 Ctrl 步长更大。
- `OreColorPicker` 编辑 `Color`：拖动色板和滑条，或直接输入十六进制颜色。
- `OreGlyph` 提供常用图形：箭头、加号、叉号、对勾、放大镜、铅笔、垃圾桶、齿轮等。自定义图标可用 16 行 `#` 与 `.` 写成 `OrePixelArt`，再交给 `OreIcon` 绘制。

## 选择

![共享同一选项的组合按钮、下拉框和单选框](../assets/ore-choices-zh-CN.png)

```kotlin
@Composable
fun DifficultyOptions() {
    val options = listOf("和平", "简单", "普通", "困难")
    var selected by remember { mutableIntStateOf(2) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OreTabButton(options, selected, { selected = it })
        OreSelect(options, options[selected], { selected = options.indexOf(it) })
        options.forEachIndexed { index, label ->
            OreRadioButton(selected == index, { selected = index }, label = label)
        }
    }
}
```

`OreCheckbox` 也接受 `ToggleableState`，可以做出带"部分选中"状态的全选框。

## 菜单

![从按钮打开的操作菜单](../assets/ore-menu-zh-CN.png)

```kotlin
@Composable
fun ActionMenu() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OreButton("操作", onClick = { expanded = true })
        OreMenu(expanded, { expanded = false }, listOf(
            OreMenuItem("copy", "复制", shortcut = "Ctrl+C") {},
            OreMenuItem("rename", "重命名") {},
            OreMenuItem("share", "分享", enabled = false) {},
            OreMenuItem("remove", "删除", destructive = true) {},
        ))
    }
}
```

菜单和触发它的按钮放在同一个 `Box` 里。`OreContextMenuArea(items) { … }` 用右键打开同样的菜单。两者都支持方向键、回车和 Esc。快捷键文字只是显示用，按键绑定需要自己注册。

## 提示

![从提示内部再打开第二层提示](../assets/ore-tooltips-zh-CN.png)

```kotlin
@Composable
fun MendingHint() {
    OreTooltip(tooltip = {
        OreText("经验修补", style = OreTheme.typography.title)
        OreText("用经验修复这件物品。")
        OreTooltip(tooltip = {
            OreText("拾取的经验球会先用于修复物品，而不是提升等级。")
        }) {
            OreText("如何生效？→")
        }
    }) {
        OreButton("经验修补", onClick = {}, style = OreButtonStyle.Secondary)
    }
}
```

指针一到，提示立即出现。保持不动，等绿色进度线走完（600 毫秒）提示就会锁定，之后可以移入提示中点击按钮或打开下一层。提示里可以放任何可组合项，包括物品图标。

## 窗口与对话框

![浮在页面上的窗口](../assets/ore-window-zh-CN.png)

```kotlin
@Composable
fun WindowExample() {
    var open by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        OreButton("打开详情", onClick = { open = true })
        if (open) OreWindow("详情", onClose = { open = false }) {
            OreText("拖动标题栏移动，拖动边缘或四角调整大小。")
        }
    }
}
```

窗口停留在所在的 `Box` 内，周围的页面仍可操作。需要玩家先作答才能继续时，改用 `OreDialog`。

## 树形与列表

![展开两级并选中一行的树形列表](../assets/ore-tree-zh-CN.png)

```kotlin
@Composable
fun SettingsTree() {
    val nodes = listOf(
        OreTreeNode("world", "世界", listOf(
            OreTreeNode("general", "常规"),
            OreTreeNode("render", "渲染", listOf(
                OreTreeNode("lighting", "光照"),
                OreTreeNode("particles", "粒子"),
            )),
        )),
        OreTreeNode("packs", "资源包"),
    )
    var selected by remember { mutableStateOf<String?>("lighting") }
    var expanded by remember { mutableStateOf(setOf("world", "render")) }
    OreTreeView(nodes, selected, { selected = it }, expanded,
        { id, open -> expanded = if (open) expanded + id else expanded - id })
}
```

树的行按需创建，上万个条目也能流畅滚动。普通列表可以把 `OreListItem` 放进 `LazyColumn`，再配一个 `OreScrollbar`。

## 主题与文字

- `OreTheme` 提供 `OreColors` 和 `OreTypography`。给界面指定 `theme = OreThemeId("yourmod", "storage")`，资源包就能为它换色；也可以选用内置的浅色或暮光主题，详见[主题](themes.md)。`OreTheme(colors = …)` 则直接使用固定颜色。
- 文字使用内置的 Monocraft 字体。它缺少的字符（例如中文）由系统字体补齐，因此显示效果取决于玩家的电脑。
- `OreSlot` 是 18 dp 的槽位框，内容区 16 dp；[容器界面](inventory.md)用它显示真实的菜单槽位。

## 在游戏中试用全部组件

把开发产物加入运行配置，启动游戏后按 **F8**：

```groovy
localRuntime "dev.compixel:compixel-neoforge-1.21.1:${compixel_version}:development"
```

![游戏中的 F8 组件预览](../assets/preview-zh-CN.png)
