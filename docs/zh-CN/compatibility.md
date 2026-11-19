# 兼容性

[English](../en/compatibility.md) · [全部指南](../README.md)

## Minecraft 版本

| Minecraft | 加载器 | Java | 图形后端 |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.2.18 及以上 | 17 | OpenGL |
| 1.21.1 | NeoForge 21.1.1 及以上 | 21 | OpenGL |
| 26.1.2 | NeoForge 26.1.2.0-beta 及以上 | 25 | OpenGL |
| 26.2 | NeoForge 26.2.0.0-beta 及以上 | 25 | OpenGL、Vulkan |
| 26.3 | NeoForge 26.3.0.0-beta 及以上 | 25 | OpenGL、Vulkan |

每个 Minecraft 版本都有单独构建的 CompixelUI，请使用与之对应的那一份。各版本的 API 包名和类名相同（包括 `dev.compixel.forge`），不同的只是周边的 Minecraft 与加载器类型。

CompixelUI 使用 Kotlin 2.4.10 和 Compose 1.12.0 构建。

## Maven 坐标

| 坐标 | 内容 |
| --- | --- |
| `dev.compixel:compixel-<加载器>-<Minecraft>` | 库本身；所需的 Compose 运行时由 Gradle 自动添加 |

1.20.1 的加载器为 `forge`，其余为 `neoforge`。每个版本还发布 `sources`、`javadoc` 和 `development`（F8 预览）分类器。请在 `repositories` 中添加 `google()`，以便 Gradle 解析运行时声明的 AndroidX 编译依赖。

### Forge 1.20.1

1.20.1 的产物使用 SRG 名称，请像其他 Forge 模组一样通过重映射配置添加。ModDevGradle Legacy 的 `mod…` 配置不会带上传递依赖，所以还要通过普通配置添加同一坐标，以引入 Compose 运行时和编译所需的 Kotlin 库；经普通配置引入的 CompixelUI 库同样会被重映射。

```groovy
dependencies {
    modCompileOnly "dev.compixel:compixel-forge-1.20.1:${compixel_version}"
    modLocalRuntime "dev.compixel:compixel-forge-1.20.1:${compixel_version}"
    compileOnly "dev.compixel:compixel-forge-1.20.1:${compixel_version}"
    localRuntime "dev.compixel:compixel-forge-1.20.1:${compixel_version}"

    additionalRuntimeClasspath "org.jetbrains.kotlin:kotlin-stdlib:2.4.10"
    additionalRuntimeClasspath "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    additionalRuntimeClasspath "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3"
}
```

`localRuntime`、`modLocalRuntime` 和 `additionalRuntimeClasspath` 均是 ModDevGradle Legacy MDK 中设置的配置。

## Kotlin

CompixelUI 需要 Kotlin 2.2.21 或更高版本，以及兼容的 Coroutines 和 Serialization 运行库。

- **玩家**：安装适配对应 Minecraft 版本的 Kotlin 运行时模组（例如 Kotlin for Forge 或其他兼容提供方）。若缺少或版本过低，游戏启动时会弹出报错页面说明原因与解决方法。
- **模组开发者**：`compileOnly` 会通过 Maven 自动解析编译所需的 Kotlin 与 AndroidX 依赖；`runClient` 时可自行引入标准 Kotlin 库（`kotlin-stdlib`、`kotlinx-coroutines-core-jvm`、`kotlinx-serialization-core-jvm`：1.20.1/1.21.1 放在 `additionalRuntimeClasspath`，26.x 可直接放在 `localRuntime`），或直接加载 Kotlin 运行时模组。

## 图形

CompixelUI 使用与游戏相同的图形接口：OpenGL；当 Minecraft 26.2 或 26.3 运行在 Vulkan 上时则使用 Vulkan。可以用 `-Dcompixel.backend` 指定 `opengl`、`vulkan` 或 `cpu`；`cpu` 是用于排查问题的慢速参考渲染器。

每个文件都包含 Windows、Linux 和 macOS 的 x64 与 arm64 原生库。

## 加载器差异

| | Forge 1.20.1 | NeoForge |
| --- | --- | --- |
| 元数据 | `mods.toml`，`mandatory = true` | `neoforge.mods.toml`，`type = "required"` |
| 菜单界面 | 在 `enqueueWork` 中调用 `MenuScreens.register` | `RegisterMenuScreensEvent` |
| HUD 层 | `RegisterGuiOverlaysEvent` | `RegisterGuiLayersEvent` |
| 菜单同步中的 Minecraft 值 | `MinecraftSyncCodecs.buffer` | `MinecraftSyncCodecs.registry` |

## 键盘与文字输入

- 字母和标点按键跟随玩家的键盘布局。文本框支持选择和剪贴板。
- 在 26.x 上，获得焦点的文本框会像原版输入框一样打开 Minecraft 的文字输入，输入法的组字过程显示在文本框内。
- 在 1.20.1 和 1.21.1 上，组字过程显示在操作系统的输入法窗口中。

## 其他模组

容器界面保留了原生容器界面及其事件，配方查看器等容器扩展可以照常工作。其他模组添加到界面上的控件绘制在 Compose 内容之上，并优先接收输入。
