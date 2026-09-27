# 用资源包修改 Ore 界面配色

[English](../en/themes.md) · [文档目录](../README.md)

资源包作者只需要创建 JSON 文件，不需要编写或阅读游戏代码。主题可以修改面板、文字、按钮、槽位等颜色；它不替换界面布局、物品贴图或原生 Minecraft 物品提示框。

## 一个文件，三个可选的配色字段

所有主题使用同一种 JSON 格式。`preset`、`palette` 和 `colors` 可以分别使用，也可以放在同一个文件里。

| 字段 | 是否必填 | 填什么 | 用来做什么 |
| --- | --- | --- | --- |
| `format` | 必填 | 数字 `1` | 声明主题文件格式版本。不是 Minecraft 版本，也不是资源包的 `pack_format`。 |
| `preset` | 可选 | `"default"` 或 `"light"` | 先换成一整套内置配色，适合整体切换深浅风格。 |
| `palette` | 可选 | 一个包含主色、次色或危险操作色的对象 | 给一个基准色，自动配出相关控件的常态、悬停、按下、边框和文字/图标颜色。 |
| `colors` | 可选 | 一个包含具体部位颜色的对象 | 精确修改某个部位，例如面板背景、正文或槽位。不会自动调整其他颜色。 |

只需写出要修改的内容。不写 `preset` 时，从已有主题继续修改；不写的其他颜色也会保留。JSON 中不能写注释或末尾多余的逗号，字段名区分大小写。

### `preset`：选择一整套内置配色

`preset` 的意思是“预设配色”。ComposeMC 目前自带以下两套，名称是固定的：

| 值 | 外观 |
| --- | --- |
| `"default"` | 原始 Ore 风格：深灰面板、浅色文字、绿色强调色。 |
| `"light"` | 浅色风格：浅灰金属面板、深色文字、紫色强调色。 |

例如，下面是一个**完整文件**，将目标主题切换为浅色：

```json
{
  "format": 1,
  "preset": "light"
}
```

`"light"` 是内置浅色配色的名称，不是文件路径。这里不能填写自定义文件名、模组 ID 或其他主题 ID；自定义配色用下面的 `palette` 和 `colors` 编写。

写入 `preset` 会重置整套颜色，再应用本文件的其他修改。因此 `"preset": "default"` 是“恢复内置深色作为起点”，省略 `preset` 才是“沿用已有配色”。

![内置浅色配色下的 Ore 控件](../assets/ore-light.png)

### `palette`：用一个颜色调整一组控件状态

`palette` 目前只接受三个名称：

| 名称 | 用途 | 自动调整的范围 |
| --- | --- | --- |
| `primary` | 主色，用于主要操作和选中状态 | 主色控件的常态、悬停、按下、边框、文字/图标；已填充滑轨；标记及悬停槽位颜色。 |
| `secondary` | 次色，用于次要按钮等控件 | 次色控件的常态、悬停、按下、边框、文字/图标，以及次要按钮的像素倒角。 |
| `danger` | 危险操作色，用于删除等操作 | 危险按钮的常态、悬停、按下、边框及文字/图标。 |

例如，只把已有主题的主色改为蓝色，其他颜色继续沿用：

```json
{
  "format": 1,
  "palette": {
    "primary": "#5688D8"
  }
}
```

库会自动生成相配的悬停、按下和边框颜色，并按基准色的明暗选择深色或白色文字/图标。面板和正文颜色不会随之改变。

### `colors`：精确修改一个部位

例如，只改面板和正文：

```json
{
  "format": 1,
  "colors": {
    "panel": "#252B38",
    "text": "#EEF2F8"
  }
}
```

`panel` 是面板背景，`text` 是正文颜色。所有可用名称及对应部位见下方的[颜色名称速查](#颜色名称速查)，无需查源码。

**`palette.primary` 与 `colors.primary` 的区别：**前者会生成整组主色状态；后者只改常态主色，悬停、按下、边框和文字/图标都保持原来的值。通常整体换强调色用 `palette`，微调细节用 `colors`。

### 三者一起使用

下面也是一个完整文件：先选浅色外观，再把紫色强调改为蓝色，最后单独指定面板和按钮悬停色。

```json
{
  "format": 1,
  "preset": "light",
  "palette": {
    "primary": "#5688D8"
  },
  "colors": {
    "panel": "#E6E9EF",
    "primaryHover": "#365C9C"
  }
}
```

同一个文件始终按 **`preset` → `palette` → `colors`** 生效，与字段书写顺序无关。这里最终的主色悬停色是 `#365C9C`，会覆盖 `palette` 自动生成的值。

## 文件放在哪里

在一个适配当前 Minecraft 版本的资源包中添加主题 JSON。资源包根目录需要已有有效的 `pack.mcmeta`；主题文件里的 `format: 1` 不能代替它。

模组作者会为界面指定一个“主题 ID”，写法如 `examplemod:storage`。冒号前的 `examplemod` 是命名空间（通常是模组 ID），后面的 `storage` 是主题名。对应路径为：

```text
你的资源包/
├── pack.mcmeta
└── assets/
    └── examplemod/
        └── composemc/
            └── ore_themes/
                └── storage.json
```

`examplemod` 和 `storage` 是示例，要换成目标模组公布的值；`assets`、`composemc`、`ore_themes` 是固定目录名。主题 ID 和路径中的字母使用小写；主题名中若包含 `/`，就对应下一级子目录。

| 想修改的范围 | 资源包内的路径 |
| --- | --- |
| 全局默认配色 | `assets/composemc/composemc/ore_themes/default.json` |
| `examplemod` 命名空间的默认配色 | `assets/examplemod/composemc/ore_themes/default.json` |
| `examplemod:storage` 这个主题 | `assets/examplemod/composemc/ore_themes/storage.json` |
| 界面直接选用的内置 `composemc:light` 主题 | `assets/composemc/composemc/ore_themes/light.json` |

**文件名必须对应界面实际使用的主题 ID。** 创建 `storage.json` 不会自动将它绑定到某个仓储界面；只有使用 `examplemod:storage` 的界面才会读取它。请从目标模组的资源包说明获取主题 ID，未公布时需要模组作者补充。模组也可能让多个界面共用同一个主题，或使用 `composemc` 命名空间，因此不能只凭模组名猜路径。

例如，当前 BeyondDimensions 接入的主题是 `beyonddimensions:storage`、`beyonddimensions:network`、`beyonddimensions:primary_network`。修改其共同默认配色可放在 `assets/beyonddimensions/composemc/ore_themes/default.json`；只改仓储主题则放在同目录的 `storage.json`。

## 多个文件如何叠加

对于 `examplemod:storage`，依次读取：

1. 内置深色配色。
2. 全局 `assets/composemc/composemc/ore_themes/default.json`。
3. 模组默认 `assets/examplemod/composemc/ore_themes/default.json`。
4. 具体主题 `assets/examplemod/composemc/ore_themes/storage.json`。

不存在的文件直接跳过。同一路径有多个来源时，会读取模组自带资源以及所有启用资源包中的文件，按资源包优先级从低到高叠加。后面的文件只修改自己写出的字段；不过 `preset` 会重置全部颜色，`palette` 会重新生成整组相关颜色，包括前面文件单独指定过的颜色。

例如，低优先级的 `storage.json` 设置了 `panel` 和 `text`，高优先级的同名文件只写 `colors.panel`，最终只替换面板色，文字色保留。

**范围优先于资源包顺序：**具体主题始终在模组默认之后应用。因此，即使你的资源包优先级更高，只修改 `default.json` 也不能覆盖具体主题文件明确写出的颜色；遇到这种情况，应覆盖同一个具体主题路径。全局默认也遵循这条规则。

`composemc:light` 是一个例外：它直接从内置浅色配色开始，再叠加 `assets/composemc/composemc/ore_themes/light.json`，不继承全局默认。修改这个 `light.json` 只影响选择 `composemc:light` 的界面；其他文件里的 `"preset": "light"` 仍使用库内置的浅色值。

## 颜色名称速查

颜色值使用 `"#RRGGBB"` 或 `"#RRGGBBAA"`。前六位表示红、绿、蓝；最后两位可选，表示不透明度：`00` 完全透明，`FF` 完全不透明。例如 `"#00000080"` 是约半透明黑色，`"#141516D9"` 是约 85% 不透明的深灰色。所有字母大小写均可，但必须带 `#`；不接受 `red`、`rgb(...)` 或 `#FFF`。

下表是 `colors` 对象支持的完整名称。按需要选择，不必全部填写。同一个颜色可能被多个控件共用，表中列出典型用途；模组自行指定固定颜色的部分不会受这些设置影响。

| 名称 | 界面上的用途 |
| --- | --- |
| `backdrop` | 界面后方遮住游戏画面的背景蒙层。 |
| `panel` | 主面板、普通输入框等区域的背景。 |
| `raised` | 凸起区域、标题栏、部分提示框的背景。 |
| `hovered` | 列表项、图标按钮等区域的悬停背景。 |
| `edge` | 通用控件暗边、分隔线。 |
| `highlight` | 面板、槽位等的亮边。 |
| `frameEdge` | 窗口外框。 |
| `ledge` | 窗口或面板底部表现厚度的边条。 |
| `slot` | 普通物品槽底色。 |
| `slotEdge` | 普通物品槽暗边。 |
| `markedSlot` | 被标记物品槽的底色。 |
| `slotHover` | 槽位悬停高亮的混合色。 |
| `slotHoverEdge` | 槽位悬停或标记时的边框基色。 |
| `text` | 普通正文、输入文字。 |
| `mutedText` | 次要说明、输入占位文字等。 |
| `disabledText` | 禁用按钮等控件的文字。 |
| `ink` | 供控件或模组使用的深色前景；修改它不会自动修改 `onSecondary`。 |
| `primary` | 主要按钮常态底色，也用于选中标记等。 |
| `primaryHover` | 主要按钮悬停底色。 |
| `primaryPressed` | 主要按钮等控件按下时的底色。 |
| `primaryEdge` | 主色控件暗边、底边。 |
| `onPrimary` | 主色背景上的文字、图标、勾选标记。 |
| `secondary` | 次要按钮等控件的常态底色。 |
| `secondaryHover` | 部分次色控件的悬停色；次要按钮面色使用 `secondaryButtonActive`。 |
| `secondaryPressed` | 部分次色控件的按下色；次要按钮面色使用 `secondaryButtonActive`。 |
| `secondaryEdge` | 次色控件暗边、底边。 |
| `onSecondary` | 次色背景上的文字、图标。 |
| `danger` | 危险按钮常态底色，也用于输入错误提示等。 |
| `dangerHover` | 危险按钮悬停底色。 |
| `dangerPressed` | 危险按钮按下底色。 |
| `dangerEdge` | 危险按钮暗边、底边。 |
| `onDanger` | 危险按钮上的文字、图标。 |
| `buttonBorder` | 按钮外框，也用于滚动条滑块外框。 |
| `secondaryButtonActive` | 次要按钮悬停及按下时的面色。 |
| `secondaryButtonLightEdge` | 次要按钮上边和左边的亮色倒角。 |
| `secondaryButtonDarkEdge` | 次要按钮下边和右边的倒角。 |
| `secondaryButtonCorner` | 次要按钮右上角、左下角的过渡像素。 |
| `bevelLight` | 控件自动混合亮边时使用的参考色，通常为白色。 |
| `switchTrack` | 开关未选中时的轨道底色，也用于未选中的单选控件。 |
| `trackFilled` | 滑动条已填充部分的底色。 |
| `trackFilledLight` | 滑动条已填充部分的亮边。 |
| `trackEmpty` | 滑动条未填充部分的底色。 |
| `trackEmptyLight` | 滑动条未填充部分的亮边，也用于滚动条轨道。 |
| `disabledEdge` | 禁用控件的边框。 |
| `focus` | 键盘焦点或输入焦点的外框。 |

`colors` 中的值不会联动：改正文 `text` 不会自动改按钮文字 `onPrimary`；改 `slot` 不会重新生成悬停色。`palette` 先于 `colors` 计算，因此本文件中的 `colors.slot`、`colors.bevelLight` 也不会倒过来影响已经生成的颜色。需要进一步微调时，直接填写对应的精确颜色。

## 在游戏中应用与排错

将资源包放入当前游戏实例的 `resourcepacks` 目录，在资源包设置中启用。修改文件后按 **F3+T** 重载即可看到新颜色，界面中的输入草稿、滚动位置和菜单绑定会保留。移除资源包并重载后，它的配色覆盖也会移除。

如果没有生效，依次检查：

1. 目标界面的主题 ID 是否与文件路径一致；是否有具体主题覆盖了 `default.json`。
2. 资源包是否启用，同一路径是否被更高优先级资源包覆盖。
3. JSON 是否有效，是否填写 `"format": 1`；如果写了 `preset`，是否为 `"default"` 或 `"light"`；颜色名称和格式是否正确。
4. 当前游戏实例的 `logs/latest.log` 是否包含 `Skipping Ore theme`。后面会列出资源路径、来源资源包和错误原因。

未知字段、错误颜色、未知预设或损坏 JSON 会使该来源的整个主题文件被跳过，有效的其他来源仍然生效。主题文件只在客户端加载。

## 模组开发者：为界面公布主题 ID

这一节用于接入代码。资源包作者完成上面的步骤即可。

```kotlin
import dev.composemc.ui.ore.theme.OreThemeId

ComposeScreen(
    Component.literal("仓储"),
    theme = OreThemeId("examplemod", "storage"),
) {
    OreScreen("仓储") { /* 控件 */ }
}
```

在模组的资源包说明中公布“界面名称 → 主题 ID → JSON 路径”，例如“仓储 → `examplemod:storage` → `assets/examplemod/composemc/ore_themes/storage.json`”。内置 JSON 放在模组的 `src/main/resources` 下同一路径，资源包即可覆盖。

`ComposeMenuScreen`、`ComposeInventoryScreen`、`ComposeHudLayer`、`ComposeConfigScreen` 也有 `theme` 参数。普通入口默认 `composemc:default`；配置界面默认使用传入的 ModContainer 对应的 `<modid>:default`。所属模组由调用方显式指定，不根据类名猜测。

对话框、菜单和 Compose 提示框继承当前主题。局部主题可用 `OreTheme(id = OreThemeId("examplemod", "inspector")) { … }`。`OreScreen` 只负责布局，使用继承的颜色；`OreThemeId.Light` 选择上文说明的 `composemc:light`。

`OreTheme(colors = …)` 可直接提供固定颜色；需要资源包控制时使用命名主题。桌面宿主可通过 `OreThemeResources` 提供不可变的 `OreThemeCatalog`。资源重载在准备阶段读取并校验文件，应用阶段发布完整快照，现有界面在 Compose 线程更新，不重建 UiSession；内容相同的快照不会触发主题重组。
