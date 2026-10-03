# 主题

[English](../en/themes.md) · [全部指南](../README.md)

Ore 界面的颜色来自主题。资源包只需几个 JSON 文件就能给界面换色，不用写任何代码。主题只管颜色：面板、文字、按钮、槽位等；界面布局、物品贴图和 Minecraft 原版的物品提示框都不受影响。

## 内置主题

CompixelUI 自带三套完整配色：

| 主题 | 外观 |
| --- | --- |
| 默认 | 原汁原味的 Ore 风格：深灰面板、浅色文字、绿色点缀 |
| 浅色 | 浅灰金属面板、深色文字、紫色点缀 |
| 暮光 | 深蓝面板、淡色文字、青色点缀 |

![浅色主题下的 Ore 控件](../assets/ore-light.png)

![暮光主题下的 Ore 控件](../assets/ore-twilight.png)

资源包可以用下文的 `preset` 把界面换成其中一套；模组也可以直接用 `OreThemeId.Light` 或 `OreThemeId.Twilight` 为界面选定。

## 编写主题文件

所有主题文件格式相同。下面是一个完整的文件，作用是把界面换成浅色：

```json
{
  "format": 1,
  "preset": "light"
}
```

| 字段 | 必填 | 取值 | 作用 |
| --- | --- | --- | --- |
| `format` | 是 | `1` | 主题文件的格式版本，与 Minecraft 版本和资源包的 `pack_format` 无关。 |
| `preset` | 否 | `"default"`、`"light"` 或 `"twilight"` | 从一套内置配色重新开始。 |
| `palette` | 否 | `primary`、`secondary` 或 `danger` 的基准色 | 为这类控件自动生成一整套配色。 |
| `colors` | 否 | 各个部位的颜色 | 逐个精确设置颜色。 |

只写想改的部分，其余颜色保持原样。JSON 里不能写注释，也不能有多余的末尾逗号；字段名区分大小写。

### `preset`：从内置配色开始

`preset` 会先把所有颜色换成指定的内置配色，再应用文件里的其他设置。所以 `"preset": "default"` 表示"回到默认深色重新开始"；想在界面现有颜色的基础上修改，就不要写 `preset`。

这里填的是内置配色的名字，不是文件路径、模组 ID 或主题 ID。自己的配色请用 `palette` 和 `colors`。

### `palette`：一次改好一类控件

只需给出一个基准色，CompixelUI 会自动推算出其余颜色：常态、悬停和按下时的底色、边框，以及看得清的文字和图标颜色。

| 名称 | 用于 | 顺带改变 |
| --- | --- | --- |
| `primary` | 主要操作和选中状态 | 滑块的已填充部分，以及被标记和悬停的槽位 |
| `secondary` | 次要按钮等控件 | 次要按钮的像素倒角 |
| `danger` | 删除等危险操作 | 无 |

下面的文件保留现有配色，只把点缀色换成蓝色：

```json
{
  "format": 1,
  "palette": {
    "primary": "#5688D8"
  }
}
```

面板和正文颜色不会变。

### `colors`：精确设置某个部位

下面的文件只改面板背景和正文颜色：

```json
{
  "format": 1,
  "colors": {
    "panel": "#252B38",
    "text": "#EEF2F8"
  }
}
```

所有可用的名称见[颜色名称](#颜色名称)。

`palette.primary` 和 `colors.primary` 不一样：前者重新生成整套主色，后者只改主色的常态颜色。换点缀色用 `palette`，微调细节用 `colors`。

### 组合使用

无论字段按什么顺序书写，同一个文件总是先应用 `preset`，再应用 `palette`，最后应用 `colors`。下面的文件从浅色配色开始，把紫色点缀换成蓝色，再精确设置两个颜色：

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

因为 `colors` 最后应用，主色的悬停色最终是 `#365C9C`。

## 文件放在哪里

把文件放进适用于你的 Minecraft 版本的资源包里。资源包照常需要 `pack.mcmeta`。

每个界面都有一个主题 ID，例如 `examplemod:storage`：冒号前是命名空间，通常就是模组 ID；冒号后是主题名。对应的文件位置是：

```text
你的资源包/
├── pack.mcmeta
└── assets/
    └── examplemod/
        └── compixel/
            └── ore_themes/
                └── storage.json
```

把 `examplemod` 和 `storage` 换成模组公布的 ID；`assets`、`compixel` 和 `ore_themes` 保持不变。ID 一律小写，主题名里的 `/` 对应一层子文件夹。

| 想改的范围 | 文件位置 |
| --- | --- |
| 所有 Ore 界面 | `assets/compixel/compixel/ore_themes/default.json` |
| 某个模组的所有界面 | `assets/examplemod/compixel/ore_themes/default.json` |
| 某一个主题 | `assets/examplemod/compixel/ore_themes/storage.json` |
| 内置浅色主题 | `assets/compixel/compixel/ore_themes/light.json` |
| 内置暮光主题 | `assets/compixel/compixel/ore_themes/twilight.json` |

文件只对使用了对应主题 ID 的界面生效。只有用到 `examplemod:storage` 的界面才会读取 `storage.json`，所以主题 ID 请查阅模组的资源包说明，不要凭模组名去猜。模组也可能让几个界面共用一个主题，或者使用 `compixel` 命名空间。

## 多个文件如何叠加

以 `examplemod:storage` 为例，颜色按以下顺序逐层叠加：

1. 内置默认配色。
2. `assets/compixel/compixel/ore_themes/default.json`，作用于所有界面。
3. `assets/examplemod/compixel/ore_themes/default.json`，作用于这个模组。
4. `assets/examplemod/compixel/ore_themes/storage.json`，作用于这个主题。

缺少的文件直接跳过。如果多个资源包里有同一个文件，它们会全部生效：先应用模组自带的那份，再按资源包优先级从低到高依次应用。每个文件只改动自己写出的字段，但 `preset` 会重置所有颜色，`palette` 会重新生成它负责的整套颜色。

例如，低优先级的 `storage.json` 设置了 `panel` 和 `text`，高优先级的只设置了 `panel`，那么面板颜色会被替换，文字颜色仍用低优先级包里的。

无论资源包顺序如何，范围更小的文件总是优先：`default.json` 覆盖不了 `storage.json` 设置的颜色。要改这个颜色，就覆盖 `storage.json` 本身。

内置的浅色和暮光主题是例外：它们从各自的内置配色开始，不读取全局的 `default.json`，只会叠加 `light.json` 或 `twilight.json`。修改这两个文件只影响选用了对应内置主题的界面，不会改变其他文件里 `"preset": "light"` 或 `"preset": "twilight"` 的效果。

## 颜色名称

颜色写成 `"#RRGGBB"`，或者带不透明度的 `"#RRGGBBAA"`：末两位从 `00`（完全透明）到 `FF`（完全不透明）。例如 `"#00000080"` 是半透明的黑色。字母大小写都可以，但必须带 `#`；`red` 这样的颜色名、`rgb(…)` 和 `#FFF` 这样的简写都不支持。

下表是 `colors` 支持的全部名称。有些颜色由多个控件共用，表中列出的是典型用途。模组自己指定了颜色的部分不受影响。

| 名称 | 出现在哪里 |
| --- | --- |
| `backdrop` | 界面后方盖住游戏画面的暗色蒙层 |
| `panel` | 主面板、输入框等的背景 |
| `raised` | 凸起区域、标题栏和部分提示框 |
| `hovered` | 悬停时的列表行、图标按钮等 |
| `edge` | 控件的暗边和分隔线 |
| `highlight` | 面板、槽位等的亮边 |
| `frameEdge` | 窗口的外框 |
| `ledge` | 窗口或面板底部体现厚度的边条 |
| `slot` | 物品槽背景 |
| `slotEdge` | 物品槽暗边 |
| `markedSlot` | 被标记的物品槽背景 |
| `slotHover` | 悬停时混入物品槽的颜色 |
| `slotHoverEdge` | 悬停或被标记的物品槽的边框 |
| `text` | 正文和输入的文字 |
| `mutedText` | 次要说明和占位文字 |
| `disabledText` | 禁用控件上的文字 |
| `ink` | 供模组使用的深色文字色；修改它不会改变 `onSecondary` |
| `primary` | 主要按钮和选中状态 |
| `primaryHover` | 悬停时的主要按钮 |
| `primaryPressed` | 按下时的主要按钮等 |
| `primaryEdge` | 主色控件的暗边和底边 |
| `onPrimary` | 主色上的文字、图标和勾选标记 |
| `secondary` | 次要按钮等 |
| `secondaryHover` | 部分次色控件的悬停色；次要按钮用 `secondaryButtonActive` |
| `secondaryPressed` | 部分次色控件的按下色；次要按钮用 `secondaryButtonActive` |
| `secondaryEdge` | 次色控件的暗边和底边 |
| `onSecondary` | 次色上的文字和图标 |
| `danger` | 危险按钮，以及输入错误提示 |
| `dangerHover` | 悬停时的危险按钮 |
| `dangerPressed` | 按下时的危险按钮 |
| `dangerEdge` | 危险按钮的暗边和底边 |
| `onDanger` | 危险按钮上的文字和图标 |
| `buttonBorder` | 按钮外框，以及滚动条滑块的外框 |
| `secondaryButtonActive` | 悬停或按下时的次要按钮面色 |
| `secondaryButtonLightEdge` | 次要按钮上边和左边的倒角 |
| `secondaryButtonDarkEdge` | 次要按钮下边和右边的倒角 |
| `secondaryButtonCorner` | 两种倒角之间的转角像素 |
| `bevelLight` | 自动生成高光时混入的颜色，通常是白色 |
| `switchTrack` | 未打开的开关轨道，以及未选中的单选框 |
| `trackFilled` | 滑块的已填充部分 |
| `trackFilledLight` | 已填充部分的亮边 |
| `trackEmpty` | 滑块的未填充部分 |
| `trackEmptyLight` | 未填充部分的亮边，以及滚动条轨道 |
| `disabledEdge` | 禁用控件的边框 |
| `focus` | 获得焦点的控件的外框 |

`colors` 里的颜色互不联动：改了 `text` 不会改 `onPrimary`，改了 `slot` 也不会重新生成悬停色。`palette` 先于 `colors` 计算，所以同一文件里的 `colors.slot` 或 `colors.bevelLight` 也不会影响自动生成的颜色。需要哪个颜色，就精确写出哪个。

## 在游戏中试用

把资源包放进游戏实例的 `resourcepacks` 文件夹并启用。改完文件后按 **F3+T** 重载：已打开的界面会直接更新颜色，输入的文字、滚动位置和菜单状态都会保留。停用资源包再重载，它的颜色就会撤掉。

改动没有生效时，依次检查：

1. 文件路径与界面使用的主题 ID 一致，也没有范围更小的文件覆盖你的颜色。
2. 资源包已启用，且没有更高优先级的资源包替换了同一个文件。
3. JSON 格式正确，写了 `"format": 1`，`preset` 是 `"default"`、`"light"` 或 `"twilight"` 之一，颜色名称和取值都拼写无误。
4. `logs/latest.log` 里是否有 `Skipping Ore theme`，后面会写明文件、来源资源包和原因。

含有未知字段、错误颜色、未知预设或 JSON 格式损坏的文件会被整个跳过，其他文件照常生效。主题文件只在客户端读取。

## 模组开发者

为每个界面指定主题 ID，资源包才能针对它换色：

```kotlin
import dev.compixel.ui.ore.theme.OreThemeId

class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("仓储"), theme = OreThemeId("examplemod", "storage")) {
    // 照常重写 snapshot、handle 和 Content
}
```

- 在模组文档里公布各个界面的主题 ID 和文件路径，例如"仓储：`examplemod:storage`，`assets/examplemod/compixel/ore_themes/storage.json`"。想自带一套配色，就把文件放在 `src/main/resources` 下的同一路径，资源包仍然可以覆盖它。
- `ComposeMenuScreen`、`ComposeInventoryScreen`、`ComposeHudLayer` 和 `ComposeConfigScreen` 同样接受 `theme` 参数。界面默认使用 `compixel:default`，配置界面默认使用所编辑模组的 `<modid>:default`。
- 传入 `OreThemeId.Light` 或 `OreThemeId.Twilight` 即可使用内置主题。
- 对话框、菜单和 Compose 提示框会沿用界面的主题。想让界面的某一部分使用别的主题，用 `OreTheme(id = OreThemeId("examplemod", "inspector")) { … }` 包起来即可。
- `OreTheme(colors = …)` 使用固定颜色，资源包无法修改。Minecraft 之外的宿主可以通过 `OreThemeResources` 提供自己的 `OreThemeCatalog`。
