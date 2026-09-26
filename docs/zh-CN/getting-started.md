# 快速开始

[English](../en/getting-started.md) · [文档目录](../README.md)

本指南将一个现有的 **NeoForge 1.21.1 Kotlin 模组**接入 Compose MC，使用 Java 21、Kotlin/Compose 编译器 2.4.10 和库版本 0.1.0-alpha.34。其他目标见[兼容性](compatibility.md)。

已经接入较早 Alpha 版本的消费者，请先按[升级说明](compatibility.md#升级已有消费者)调整请求结果 API、传输配置和协议版本，再重新构建。

## 1. 构建本库

在 Compose MC 仓库中运行：

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:build :minecraft:neoforge-1.21.1:publishLibraryPublicationToConsumerRepository
```

这会在 `build/consumer-maven` 生成本地 Maven 仓库。以下配置使用该仓库，不假定当前版本已在 Maven Central 发布。工具链和其他操作系统的命令写法见[构建与测试](build-and-test.md)。

## 2. 配置消费者依赖

将以下声明合并到消费者现有的 Groovy `build.gradle` 中，保留原来的加载器插件和 Minecraft 配置。其中 `localRuntime` 由 NeoForge ModDevGradle 提供。

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.4.10'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.4.10'
}
repositories {
    maven {
        url = uri('../compose-mc/build/consumer-maven')
        content { includeGroup 'dev.composemc' }
    }
}
dependencies {
    compileOnly "dev.composemc:composemc-neoforge-1.21.1:${composemc_version}:dev"
    localRuntime "dev.composemc:composemc-neoforge-1.21.1:${composemc_version}:with-kotlin"
}
kotlin { jvmToolchain(21) }
```

按实际目录调整仓库路径。在消费者的 `gradle.properties` 中加入：

```properties
composemc_version=0.1.0-alpha.34
kotlin.stdlib.default.dependency=false
```

编译包为两种安装方式提供相同的 Kotlin/Compose API。示例选择 `with-kotlin`，无需外部 Kotlin 提供者。如果整合包使用 Kotlin for Forge，去掉 `with-kotlin` 分类并单独安装兼容的 KFF。不要同时安装两个版本，也不要将 `with-kotlin` 与 KFF 混装。Kotlin 和 Compose 编译器插件应保持相同版本；消费者不要再内嵌这些运行时。

在 NeoForge 1.21.1 开发启动中加载 KFF，可在仓库配置加入 `maven { url = 'https://api.modrinth.com/maven' }`，在依赖中加入 `localRuntime 'maven.modrinth:kotlin-for-forge:5.12.0'`。这是开发运行依赖，不是 JarJar 依赖。已验证提供者和运行时要求见[兼容性](compatibility.md#kotlin-运行时提供者)。

在 `META-INF/neoforge.mods.toml` 中声明依赖，替换 `your_mod_id`：

```toml
[[dependencies.your_mod_id]]
modId="composemc"
type="required"
versionRange="[0.1.0-alpha.34]"
ordering="AFTER"
side="CLIENT"
```

使用菜单同步或公共槽位操作时，改为 `side="BOTH"`。处理元数据模板时，应从同一个 Gradle 属性生成版本范围。Forge 1.20.1 使用 `META-INF/mods.toml`，并以 `mandatory=true` 代替 `type="required"`。

## 3. 打开一个屏幕

将以下文件放在消费者的客户端代码中，从客户端线程事件或按键处理器调用 `openCounterScreen()`。宿主会自动提供 `OreTheme` 和游戏点击反馈。Compose MC 的宿主在所有目标上都不绘制 Minecraft 的菜单背景、模糊或压暗，`OreScreen` 自带背景遮罩。

```kotlin
import androidx.compose.runtime.*
import dev.composemc.forge.ComposeScreen
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.layout.OreScreen
import dev.composemc.ui.ore.theme.OreTheme
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

fun openCounterScreen() {
    val minecraft = Minecraft.getInstance()
    val parent = minecraft.screen
    minecraft.setScreen(ComposeScreen(
        title = Component.literal("计数器"),
        parent = parent,
    ) {
        var count by remember { mutableStateOf(0) }
        OreScreen("计数器") {
            OreText("计数：$count")
            OreButton("加一", onClick = { count++ })
        }
    })
}
```

这里的计数属于 UI 本地状态。游戏对象在进入组合之前捕获；修改游戏状态的回调需要使用下面的桥接方式。屏幕的构造与注册应放在客户端专用入口，避免专用服务器加载 UI 类。

## 4. 连接游戏状态

`dev.composemc.host.UiBinding<S, A>` 将不可变快照传入 Compose，并将类型化动作传回创建它的线程：

| 操作 | 调用位置 |
| --- | --- |
| 构造 `UiBinding(initialSnapshot)` | 客户端游戏线程 |
| 读取 `binding.value` | 组合函数内部 |
| `binding.send(action)` | UI 回调 |
| `binding.drainActions(handler)` 后调用 `binding.update(snapshot)` | 游戏线程 tick |
| `binding.close()` | 逻辑所有者最终关闭时，在游戏线程调用 |

关闭后或有界队列已满时，`send` 返回 false；默认队列容量为 64。对于重要动作，应处理拒绝结果。相等快照不会触发新的发布。保持数据不可变，并复用未变化的集合。搜索文本、焦点和弹层可见性等 UI 本地状态可以保留在 Compose 中。

不要在组合函数中读取实时菜单、`ItemStack` 或 `Minecraft`，也不要从 Compose 同步等待游戏线程：游戏线程此时可能正在等待组合完成。[临时进入配方界面](inventory.md)与最终关闭菜单绑定是不同的生命周期事件。

## 5. 安装与预览

| 产物 | 用途 |
| --- | --- |
| `composemc-neoforge-1.21.1-0.1.0-alpha.34.jar` | 标准版：与消费者和兼容的外部 Kotlin 提供者一起安装 |
| `…-with-kotlin.jar` | 另一种安装选择：自带 Kotlin，无需外部提供者 |
| `…-dev.jar` | 仅用于编译的 API 包；不要安装 |
| `…-development.jar` | 可选 F8 预览/探针模组；需要正式库 |
| `…-sources.jar` / `…-javadoc.jar` | 共用的源码/API 文档附件，也用于 `dev` |

可安装产物位于适配器的 `build/libs/`。在消费者旁边只安装一个库版本；标准版还需要外部运行时提供者。两种安装包具有相同的 Mod ID 和 API。查看组件时可额外安装开发包，它不包含第二份运行时。

源码和文档 JAR 由同一个 Maven 发布任务提供，属于 IDE 附件，不是运行依赖或模组。在 IDEA 开启源码/文档下载并刷新 Gradle，即可从 `dev` 浏览 Compose MC 自有源码和生成的 API 参考。第三方库仍使用各自上游源码及文档。

接下来可以阅读 [Ore UI](ore-ui.md)、[原生物品](native-content.md)、[HUD 层](hud.md)、[容器界面](inventory.md)或[服务端菜单同步](menu-sync.md)。
