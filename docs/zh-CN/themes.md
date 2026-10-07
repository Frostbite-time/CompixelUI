# 配色

[English](../en/themes.md) · [全部指南](../README.md)

CompixelUI 界面的颜色来自配色。玩家可以在游戏里切换配色、修改任意颜色，资源包和整合包用 JSON 文件增加或修改配色。

颜色归各个模组所有：同一个模组的所有界面共用一份配色列表。直接使用 Ore 风格的界面用 CompixelUI 自己的列表，命名空间是 `compixel`。见[模组开发者](#模组开发者)。

## 内置配色

Ore 自带三套配色：

| 配色 | 外观 |
| --- | --- |
| 默认 | 原汁原味的 Ore 风格：深灰面板、浅色文字、绿色点缀 |
| 浅色 | 浅灰金属面板、深色文字、紫色点缀 |
| 暮光 | 深蓝面板、淡色文字、青色点缀 |

![浅色配色下的 Ore 控件](../assets/ore-light.png)

![暮光配色下的 Ore 控件](../assets/ore-twilight.png)

以自己的名义使用 Ore 风格的模组，在自己的配色之外也提供这三套。

## 在游戏中修改颜色

各个模组决定从哪里打开颜色编辑器，例如标题栏上的调色板按钮。CompixelUI 自己的配色从模组列表里 CompixelUI 的**配置**按钮打开。编辑器在模组界面的预览旁列出它的颜色：

1. 在顶部选择配色。
2. 在列表里选一个颜色，用取色器修改。
3. **恢复**把选中的颜色还原为配色自己的颜色，**全部恢复**还原这套配色里改过的所有颜色。

跟随其他颜色的颜色，例如按钮的悬停色，列在**跟随其他颜色的色调**下，随所跟随的颜色变化。自己改过之后，它就保持你的颜色，直到恢复。

你的选择保存在 `config/compixel-colors.json` 里，叠加在所有资源包之上。

### 导出给资源包或整合包

**导出**会把你的颜色写成 `resourcepacks` 文件夹里的一个资源包文件夹，以你填写的名称命名：

| 选项 | 导出的资源包 |
| --- | --- |
| 覆盖「*某配色*」 | 把这套配色改成你的颜色。 |
| 另存为新配色 | 增加一套以这个名称命名的配色：从当前配色开始，带上你的颜色。 |
| 设为默认配色 | 同时把导出的配色设为玩家自己选择之前使用的配色。 |

启用这个资源包即可使用，也可以分享出去或放进整合包。

## 编写配色文件

配色是资源包里的一个 JSON 文件，位于 `assets/<命名空间>/compixel/schemes/<名称>.json`，命名空间就是模组 ID。名称一律小写，名称里的 `/` 对应一层子文件夹。

```text
你的资源包/
├── pack.mcmeta
└── assets/
    └── compixel/
        └── compixel/
            └── schemes/
                └── ocean.json
```

下面的文件为 CompixelUI 的界面增加一套"海洋"配色，从默认配色开始：

```json
{
  "format": 1,
  "name": "海洋",
  "extends": "default",
  "colors": {
    "panel": "#1F2A38",
    "raised": "#2A3A4E",
    "primary": "#2F7FB8"
  }
}
```

| 字段 | 必填 | 取值 | 作用 |
| --- | --- | --- | --- |
| `format` | 是 | `1` | 文件的格式版本。 |
| `name` | 否 | 名称，或语言文件里的翻译键 | 编辑器里显示的配色名；不写时显示文件名。 |
| `extends` | 否 | 同一模组的另一套配色，例如 `"default"` | 从那套配色的颜色开始。 |
| `colors` | 否 | 按名称列出的颜色 | 设置这些颜色。 |

颜色写成 `"#RRGGBB"`，或者带不透明度的 `"#RRGGBBAA"`：末两位从 `00`（完全透明）到 `FF`（完全不透明）。只写要改的颜色。

想修改已有的配色，就在资源包里放一个同名文件，例如内置浅色配色对应 `assets/compixel/compixel/schemes/light.json`。

### 跟随其他颜色的颜色

有些颜色按规则跟随别的颜色：文件改了 `primary`，主要按钮的悬停色、按下色、边框和按钮上的文字颜色都会跟着变。文件也可以直接设置这类颜色；更高优先级的资源包或玩家改了它所跟随的颜色后，它重新跟随。各个颜色跟随什么，见 [Ore 颜色名称](#ore-颜色名称)。

## 选择默认配色和顺序

`assets/<命名空间>/compixel/schemes.json` 决定玩家自己选择之前模组使用哪套配色，以及编辑器列表的顺序：

```json
{
  "format": 1,
  "default": "ocean",
  "order": ["ocean", "default", "light", "twilight"]
}
```

| 字段 | 必填 | 取值 | 作用 |
| --- | --- | --- | --- |
| `format` | 是 | `1` | 文件的格式版本。 |
| `default` | 否 | 模组的一套配色 | 玩家自己选择之前使用的配色。 |
| `order` | 否 | 配色列表 | 编辑器先列出这些配色，模组的默认配色、内置配色和其他文件排在后面。 |

## 多个资源包如何叠加

一套配色的颜色按以下顺序逐层叠加：

1. 模组自己的颜色。
2. 它 `extends` 的配色（如有），按同样的方式叠加而成。
3. 内置配色自己的颜色。
4. 各个资源包里这套配色的文件：先是模组自带的那份，最后是优先级最高的资源包。
5. 玩家在颜色编辑器里的修改。

每个文件只改动自己写出的颜色。`name`、`extends` 以及 `schemes.json` 的各个字段，以设置了它的优先级最高的资源包为准。

## Ore 颜色名称

| 名称 | 出现在哪里 |
| --- | --- |
| `backdrop` | 界面后方盖住游戏画面的暗色蒙层 |
| `scrim` | 对话框后方的暗色蒙层 |
| `panel` | 主面板、输入框等的背景 |
| `raised` | 凸起区域、标题栏和部分提示框 |
| `hovered` | 悬停时的列表行、图标按钮等 |
| `edge` | 控件的暗边和分隔线 |
| `highlight` | 面板、槽位等的亮边 |
| `frameEdge` | 窗口的外框 |
| `ledge` | 窗口或面板底部体现厚度的边条 |
| `bevelLight` | 自动生成高光时混入的颜色，通常是白色 |
| `text` | 正文和输入的文字 |
| `mutedText` | 次要说明和占位文字 |
| `disabledText` | 禁用控件上的文字 |
| `disabledEdge` | 禁用控件的边框 |
| `ink` | 供模组使用的深色文字色 |
| `focus` | 获得焦点的控件的外框 |
| `slot` | 物品槽背景 |
| `slotEdge` | 物品槽暗边 |
| `primary` | 主要按钮和选中状态 |
| `secondary` | 次要按钮等 |
| `buttonBorder` | 按钮外框，以及滚动条滑块的外框 |
| `danger` | 危险按钮，以及输入错误提示 |
| `switchTrack` | 未打开的开关轨道，以及未选中的单选框 |
| `trackEmpty` | 滑块的未填充部分 |
| `trackEmptyLight` | 未填充部分的亮边，以及滚动条轨道 |

下面这些颜色跟随别的颜色，直到文件或玩家设置它们：

| 名称 | 出现在哪里 | 跟随 |
| --- | --- | --- |
| `selection` | 输入框里选中的文字 | `primary` |
| `markedSlot` | 被标记的物品槽背景 | `slot`、`primary` |
| `slotHover` | 悬停时混入物品槽的颜色 | `slot`、`primary` |
| `slotHoverEdge` | 悬停或被标记的物品槽的边框 | `primary`、`bevelLight` |
| `primaryHover` | 悬停时的主要按钮 | `primary` |
| `primaryPressed` | 按下时的主要按钮等 | `primary` |
| `primaryEdge` | 主色控件的暗边和底边 | `primary` |
| `onPrimary` | 主色上的文字、图标和勾选标记 | `primary` |
| `trackFilled` | 滑块的已填充部分 | `primary` |
| `trackFilledLight` | 已填充部分的亮边 | `primary`、`bevelLight` |
| `secondaryHover` | 部分次色控件的悬停色；次要按钮用 `secondaryButtonActive` | `secondary` |
| `secondaryPressed` | 部分次色控件的按下色；次要按钮用 `secondaryButtonActive` | `secondary` |
| `secondaryEdge` | 次色控件的暗边和底边 | `secondary` |
| `onSecondary` | 次色上的文字和图标 | `secondary` |
| `secondaryButtonActive` | 悬停或按下时的次要按钮面色 | `secondary` |
| `secondaryButtonLightEdge` | 次要按钮上边和左边的倒角 | `secondary`、`bevelLight` |
| `secondaryButtonDarkEdge` | 次要按钮下边和右边的倒角 | `secondary`、`bevelLight` |
| `secondaryButtonCorner` | 两种倒角之间的转角像素 | `secondary`、`bevelLight` |
| `dangerHover` | 悬停时的危险按钮 | `danger` |
| `dangerPressed` | 按下时的危险按钮 | `danger` |
| `dangerEdge` | 危险按钮的暗边和底边 | `danger` |
| `onDanger` | 危险按钮上的文字和图标 | `danger` |

## 在游戏中试用

把资源包放进游戏实例的 `resourcepacks` 文件夹并启用。改完文件后按 **F3+T** 重载：已打开的界面会直接更新颜色，并保留自己的状态。

改动没有生效时，检查：

1. 文件位于模组的命名空间下，即 `assets/<命名空间>/compixel/schemes/`。
2. 用的正是这套配色：在颜色编辑器里选中，或在 `schemes.json` 里设为默认。
3. 没有更高优先级的资源包、也没有颜色编辑器里的修改设置了同一个颜色。
4. `logs/latest.log` 里没有关于这个文件的 `Skipping` 或 `Ignoring`；有的话，后面会写明原因。

JSON 格式损坏或 `format` 不对的文件会被跳过；未知的字段或颜色、错误的取值只会单独被忽略。

## 模组开发者

### 让界面有自己的配色

界面默认使用 Ore 和 CompixelUI 的配色。写上模组的名称，它的界面就有一份自己的配色列表：

```kotlin
class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("仓储"), design = OreDesign("examplemod")) {
    // 照常重写 snapshot、handle 和 Content
}
```

`ComposeMenuScreen`、`ComposeInventoryScreen` 和 `ComposeHudLayer` 同样接受 `design` 参数。想自带配色，就把文件放在 `src/main/resources/assets/examplemod/compixel/schemes/` 下，再用一个 `schemes.json` 选择默认配色和顺序。

### 打开颜色编辑器

界面提供 `LocalColorEditor`，调用它会在当前界面之上打开这个界面所用设计的颜色编辑器。HUD 层里它是 null。

```kotlin
LocalColorEditor.current?.let { openColorEditor -> OreButton("配色", openColorEditor) }
```

其他代码也可以直接打开 `ColorEditorScreen(parent, design)`。

### 声明自己的颜色

模组自己绘制的部分，请把它们的颜色声明在自己的颜色表里：

```kotlin
object StorageColors : ColorSchema("examplemod") {
    val window = color("window", "window", Color(0xE0101418))
    val text = color("text", "text", Color(0xFFF2F5F9))
    val accent = color("accent", "accent", Color(0xFF22C7F0))
    val accentHover = derived("accentHover", "accent", accent) { lerp(it[accent], Color.Black, .16f) }
}
```

- `color(key, group, default)` 声明一个颜色：它在配色文件里的名称、编辑器列出它时所在的分组，以及默认值。编辑器按声明的先后列出颜色。
- `derived(key, group, follows…) { rule }` 声明一个跟随别的颜色的颜色；规则只读取它所跟随的颜色。可选的 `default` 给它一个自己的颜色，直到它所跟随的颜色被改动。
- 编辑器从语言文件里取颜色和分组的名称：`color.examplemod.accent` 和 `color.examplemod.group.accent`，中间是颜色表的名称。

再给界面一套提供这些颜色的设计：

```kotlin
val LocalStorageColors = staticCompositionLocalOf { StorageColors.defaults }

object StorageDesign : UiDesign {
    override val owner = SchemeOwner("examplemod", StorageColors)

    @Composable
    override fun Decorate(content: @Composable () -> Unit) {
        val colors = owner.colors()
        // 用到的 Ore 控件从你的颜色取色
        val ore =
            remember(colors) {
                OreColors.values(
                    OreColors.panel to colors[StorageColors.window],
                    OreColors.text to colors[StorageColors.text],
                    OreColors.primary to colors[StorageColors.accent],
                )
            }
        OreTheme(ore) { CompositionLocalProvider(LocalStorageColors provides colors, content = content) }
    }

    override fun preview(): @Composable () -> Unit = { StoragePreview() }
}

class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("仓储"), design = StorageDesign)
```

- 用 `LocalStorageColors.current[StorageColors.accent]` 读取颜色。
- `owner` 指定设计的配色文件和玩家选择所在的命名空间。它的默认配色是 `default`，没有文件定义它时就是颜色表的默认值；`SchemeOwner("examplemod", StorageColors, default = "dark")` 则改从你的 `dark.json` 开始。
- `preview()` 返回给编辑器的一小块界面样例。编辑器在游戏线程调用它，所以在这里用 `ItemIcon.snapshot` 取出物品。预览最好与一个窗口差不多大：Minecraft 自动界面缩放下，1080p 屏幕留给它的高度约 230 个单位，更高的预览会滚动。
- `OreColors.values(...)` 精确设置 Ore 的颜色；其余的保持 Ore 的默认值，或跟随你设置的颜色。

`OreTheme(OreColors.scheme("light")) { … }` 使用固定颜色。Minecraft 之外的宿主通过 `LocalSchemes` 以 `Schemes` 的形式提供配色文件和玩家的选择。

自己的控件被用户触发时（例如在 `onClick` 之后）调用 `LocalUiFeedback.current.activate()`，就能像 Ore 按钮一样发出原版的点击声；界面会在游戏线程播放它。
