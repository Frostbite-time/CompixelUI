# 兼容性

[English](../en/compatibility.md) · [全部指南](../README.md)

本页说明 CompixelUI **0.1.5** 的兼容情况。

## Minecraft 版本

| Minecraft | 加载器 | Java | 图形后端 |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.2.18 及以上 | 17 | OpenGL |
| 1.21.1 | NeoForge 21.1.1 及以上 | 21 | OpenGL |
| 26.1.2 | NeoForge 26.1.2.0-beta 及以上 | 25 | OpenGL |
| 26.2 | NeoForge 26.2.0.0-beta 及以上 | 25 | OpenGL、Vulkan |
| 26.3 | NeoForge 26.3.0.0-beta 及以上 | 25 | OpenGL、Vulkan |

每个 Minecraft 版本都有单独构建的 CompixelUI，请使用与之对应的那一份。各版本的 API 包名和类名相同（包括 `dev.compixel.forge`），不同的只是周边的 Minecraft 与加载器类型。

表中的加载器版本是经过验证的最低版本。CompixelUI 的日常开发和测试使用 Forge 47.4.23，以及 NeoForge 21.1.250、26.1.2.109、26.2.0.88 和 26.3.0.6-beta，并使用 Kotlin 2.4.10 和 Compose 1.12.0 构建。

## Maven 坐标

| 坐标 | 内容 |
| --- | --- |
| `dev.compixel:compixel-<加载器>-<Minecraft>` | 库本身；所需的 Compose 运行时由 Gradle 自动添加 |
| `dev.compixel:compixel-<加载器>-<Minecraft>-with-kotlin` | 同上，另加 Kotlin 库 |

1.20.1 的加载器为 `forge`，其余为 `neoforge`。1.20.1 的产物使用 SRG 名称，请像其他 Forge 模组一样通过构建工具的重映射配置添加。每个版本还发布 `sources`、`javadoc` 和 `development`（F8 预览）分类器。

## Kotlin

每个版本为玩家提供两个文件：

| 文件 | Kotlin |
| --- | --- |
| `…-with-kotlin.jar` | 已包含，不要与 Kotlin for Forge 同时安装 |
| `….jar` | 需要 Kotlin 提供方，例如 Kotlin for Forge |

经过测试的 Kotlin for Forge 版本：1.20.1 为 4.12.0，1.21.1 为 5.12.0，26.1.2 和 26.2 为 6.3.0。Kotlin for Forge 6.3.0 不支持 26.3，这个版本请使用 `-with-kotlin` 文件。任何提供方都需要 Kotlin 2.2.21 或更高版本，以及相匹配的 Coroutines 和 Serialization 库；缺少时游戏会在启动时给出明确的错误。

## 图形

CompixelUI 使用与游戏相同的图形接口：OpenGL；当 Minecraft 26.2 或 26.3 运行在 Vulkan 上时则使用 Vulkan。可以用 `-Dcompixel.backend` 指定 `opengl`、`vulkan` 或 `cpu`；`cpu` 是用于排查问题的慢速参考渲染器。

每个文件都包含 Windows、Linux 和 macOS 的 x64 与 arm64 原生库。目前在真实硬件上的测试覆盖了 Windows x64 搭配 NVIDIA 显卡。其他系统、显卡驱动和光影模组预期可以工作，但尚未验证。

## 加载器差异

| | Forge 1.20.1 | NeoForge |
| --- | --- | --- |
| 元数据 | `mods.toml`，`mandatory = true` | `neoforge.mods.toml`，`type = "required"` |
| 菜单界面 | 在 `enqueueWork` 中调用 `MenuScreens.register` | `RegisterMenuScreensEvent` |
| HUD 层 | `RegisterGuiOverlaysEvent` | `RegisterGuiLayersEvent` |
| 配置界面 | `ConfigScreenHandler.ConfigScreenFactory` | `IConfigScreenFactory` |
| 菜单同步中的 Minecraft 值 | `MinecraftSyncCodecs.buffer` | `MinecraftSyncCodecs.registry` |

## 键盘与文字输入

- 字母和标点按键跟随玩家的键盘布局。文本框支持选择和剪贴板。
- 在 26.x 上，获得焦点的文本框会像原版输入框一样打开 Minecraft 的文字输入，输入法的组字过程显示在文本框内。
- 在 1.20.1 和 1.21.1 上，组字过程显示在操作系统的输入法窗口中。

## 其他模组

容器界面保留了原生容器界面及其事件，配方查看器等容器扩展可以照常工作。其他模组添加到界面上的控件绘制在 Compose 内容之上，并优先接收输入。请测试你的整合包所依赖的组合；光影和大量修改 GUI 的模组尚未广泛测试。
