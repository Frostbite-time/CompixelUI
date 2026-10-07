# 配色

[English](../en/themes.md) · [全部指南](../README.md)

CompixelUI 界面的颜色来自配色。玩家可以在游戏里切换配色、修改任意颜色；资源包和整合包只需几个 JSON 文件就能增加或修改配色，不用写任何代码。配色只管颜色：界面布局、物品贴图和 Minecraft 原版的物品提示框都不受影响。

颜色归各个模组所有。同一个模组的所有界面共用一份配色列表：选一套配色，这个模组的界面全部跟着换，其他模组的界面不受影响。直接使用 Ore 风格的界面属于 CompixelUI 自己，命名空间是 `compixel`；有自己设计的模组则有自己的配色列表，见[模组开发者](#模组开发者)。

## 内置配色

Ore 自带三套配色：

| 配色 | 外观 |
| --- | --- |
| 默认 | 原汁原味的 Ore 风格：深灰面板、浅色文字、绿色点缀 |
| 浅色 | 浅灰金属面板、深色文字、紫色点缀 |
| 暮光 | 深蓝面板、淡色文字、青色点缀 |

![浅色配色下的 Ore 控件](../assets/ore-light.png)

![暮光配色下的 Ore 控件](../assets/ore-twilight.png)

以自己的名义使用 Ore 风格的模组，除了自己的配色，也提供这三套。

## 在游戏中修改颜色

界面可以打开颜色编辑器，例如模组标题栏上的调色板按钮；入口放在哪里由各个模组决定。CompixelUI 自己的配色（直接使用 Ore 风格的界面用的就是它）从模组列表里 CompixelUI 的**配置**按钮打开。编辑器左侧是这个模组界面的预览，右侧是它的颜色：

1. 在顶部选择配色，这个模组已打开的界面会立即换色。
2. 在列表里选一个颜色，用取色器修改：饱和度与亮度、色相、不透明度。
3. 改过的颜色在列表里有标记。**恢复**把选中的颜色还原为配色自己的颜色，**全部恢复**还原这套配色里改过的所有颜色。

有些颜色跟随别的颜色，例如按钮的悬停色和按下色比按钮深一级。它们列在**跟随其他颜色的色调**下，随所跟随的颜色变化；自己改过之后，就保持你的颜色，直到恢复。

你的选择按模组和配色保存在 `config/compixel-colors.json` 里，叠加在所有资源包之上。

### 导出给资源包或整合包

**导出**会把你的颜色写成 `resourcepacks` 文件夹里的一个资源包文件夹，以你填写的名称命名：

| 选项 | 导出的资源包 |
| --- | --- |
| 覆盖「*某配色*」 | 启用它的人，这套配色都会变成你的颜色。 |
| 另存为新配色 | 增加一套以你填写的名称命名的配色：从当前配色开始，带上你的颜色。 |
| 设为默认配色 | 同时把导出的配色设为玩家自己选择之前使用的配色。 |

启用这个资源包即可使用；也可以分享给其他玩家，或放进整合包。默认配色只对还没给这个模组选过配色的玩家生效。

## 编写配色文件

配色是资源包里的一个 JSON 文件，位于 `assets/<命名空间>/compixel/schemes/<名称>.json`。命名空间就是模组 ID，CompixelUI 自己的界面是 `compixel`；`assets`、`compixel` 和 `schemes` 保持不变。名称一律小写，名称里的 `/` 对应一层子文件夹。

```text
你的资源包/
├── pack.mcmeta
└── assets/
    └── compixel/
        └── compixel/
            └── schemes/
                └── ocean.json
```

下面的文件为 CompixelUI 的界面增加一套"海洋"配色：从默认配色开始，改动三个颜色。

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
| `format` | 是 | `1` | 文件的格式版本，与 Minecraft 版本和资源包格式无关。 |
| `name` | 否 | 名称，或语言文件里的翻译键 | 编辑器里显示的配色名。不写时显示文件名。 |
| `extends` | 否 | 同一模组的另一套配色，例如 `"default"` | 从那套配色的颜色开始。 |
| `colors` | 否 | 按名称列出的颜色 | 设置这些颜色。 |

颜色写成 `"#RRGGBB"`，或者带不透明度的 `"#RRGGBBAA"`：末两位从 `00`（完全透明）到 `FF`（完全不透明）。例如 `"#00000080"` 是半透明的黑色。只写想改的颜色；其余颜色沿用所继承配色的颜色，或模组自己的颜色。

想修改已有的配色，就在资源包里放一个同名文件，例如内置浅色配色对应 `assets/compixel/compixel/schemes/light.json`。文件里只需 `format` 和要改的颜色。

### 跟随其他颜色的颜色

有些颜色按规则跟随别的颜色，例如比按钮深一级的悬停色。文件改了 `primary`，主要按钮的悬停色、按下色、边框和按钮上的文字颜色都会跟着变。文件也可以直接设置这类颜色：此后它保持这个颜色，直到更高优先级的资源包或玩家改了它所跟随的颜色，再从那里重新跟随。各个颜色跟随什么，见 [Ore 颜色名称](#ore-颜色名称)。

## 选择默认配色和顺序

可选的 `assets/<命名空间>/compixel/schemes.json` 决定玩家自己选择之前模组使用哪套配色，以及编辑器列表的顺序：

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

每个文件只改动自己写出的颜色。例如，低优先级的 `ocean.json` 设置了 `panel` 和 `text`，高优先级的只设置了 `panel`，那么面板颜色会被替换，文字颜色仍用低优先级包里的。`name`、`extends` 以及 `schemes.json` 的各个字段，以设置了它的优先级最高的资源包为准。

## Ore 颜色名称

下表是 Ore 的颜色在 `colors` 里的名称及其典型用途；有些颜色由多个控件共用。

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

把资源包放进游戏实例的 `resourcepacks` 文件夹并启用。改完文件后按 **F3+T** 重载：已打开的界面会直接更新颜色，输入的文字、滚动位置和菜单状态都会保留。

改动没有生效时，依次检查：

1. 文件位于模组的命名空间下，即 `assets/<命名空间>/compixel/schemes/`；各模组会在文档里写明自己的命名空间。
2. 用的正是这套配色：在颜色编辑器里选中它，或在 `schemes.json` 里把它设为默认。玩家选过的配色优先于默认配色。
3. 资源包已启用，且没有更高优先级的资源包设置了同一个颜色。
4. 玩家没有在颜色编辑器里改过同一个颜色；**恢复**可以撤掉这样的修改。
5. JSON 格式正确，写了 `"format": 1`，颜色名称和取值都拼写无误。
6. `logs/latest.log` 里是否有 `Skipping` 或 `Ignoring`，后面会写明文件、来源资源包和原因。

JSON 格式损坏或 `format` 不对的文件会被整个跳过。未知的字段或颜色名称、错误的取值只会单独被忽略，文件的其余部分照常生效。配色文件只在客户端读取。

## 模组开发者

### 让界面有自己的配色

界面默认使用 Ore 和 CompixelUI 的配色。写上模组的名称，它的界面就有一份自己的配色列表，玩家和资源包修改它时不会影响其他模组：

```kotlin
class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("仓储"), design = OreDesign("examplemod")) {
    // 照常重写 snapshot、handle 和 Content
}
```

`ComposeMenuScreen`、`ComposeInventoryScreen` 和 `ComposeHudLayer` 同样接受 `design` 参数。想自带配色，就把文件放在 `src/main/resources/assets/examplemod/compixel/schemes/` 下，并在同一位置放一个 `schemes.json` 选择默认配色和顺序。资源包仍然可以修改它们。

### 打开颜色编辑器

界面提供 `LocalColorEditor`。调用它，就会在当前界面之上打开这个界面所用设计的颜色编辑器，玩家关闭编辑器后回到原界面。无法打开编辑器的地方（例如 HUD 层）它是 null。

```kotlin
LocalColorEditor.current?.let { openColorEditor -> OreButton("配色", openColorEditor) }
```

其他代码也可以直接打开 `ColorEditorScreen(parent, design)`。

### 声明自己的颜色

模组自己绘制的部分，请把它们的颜色声明在自己的颜色表里，不要借用 Ore 的颜色，这样玩家和资源包才能修改它们：

```kotlin
object StorageColors : ColorSchema("examplemod") {
    val window = color("window", "window", Color(0xE0101418))
    val text = color("text", "text", Color(0xFFF2F5F9))
    val accent = color("accent", "accent", Color(0xFF22C7F0))
    val accentHover = derived("accentHover", "accent", accent) { lerp(it[accent], Color.Black, .16f) }
}
```

- `color(key, group, default)` 声明一个颜色：它在配色文件里的名称、编辑器列出它时所在的分组，以及默认值。编辑器按声明的先后列出颜色。
- `derived(key, group, follows…) { rule }` 声明一个跟随别的颜色的颜色；规则只能读取它所跟随的颜色。可选的 `default` 给它一个自己的颜色，直到它所跟随的颜色被改动。编辑器把这类颜色列在**跟随其他颜色的色调**下。
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

- 用 `LocalStorageColors.current[StorageColors.accent]` 读取颜色。配色或颜色每次变化，已打开的界面都会跟着换色，并保留自己的状态。
- `owner` 指定设计的配色文件和玩家选择所在的命名空间。没有文件定义名为 `default` 的配色时，它就是颜色表的默认值；`SchemeOwner("examplemod", StorageColors, default = "dark")` 则改从你的 `dark.json` 开始。
- `preview()` 给编辑器一小块界面样例，显示在颜色旁边。编辑器打开时在游戏线程调用它，所以可以在这里用 `ItemIcon.snapshot` 取出物品、读取翻译，再返回内容。编辑器按预览自身的尺寸显示它，屏幕更矮时让它滚动。Minecraft 自动界面缩放下，1080p 屏幕只有 270 个单位高，留给预览的约 230，所以预览最好与你的一个窗口差不多大。
- `OreColors.values(...)` 精确设置 Ore 的颜色；没设置的 Ore 颜色保持 Ore 的默认值，或跟随你设置的颜色。

`OreTheme(OreColors.scheme("light")) { … }` 使用固定颜色，资源包和玩家都无法修改。Minecraft 之外的宿主通过 `LocalSchemes` 以 `Schemes` 的形式提供配色文件和玩家的选择。

自己的控件被用户触发时（例如在 `onClick` 之后）调用 `LocalUiFeedback.current.activate()`，就能像 Ore 按钮一样发出原版的点击声；界面会在游戏线程播放它。
