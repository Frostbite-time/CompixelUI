# 快速开始

[English](../en/getting-started.md) · [全部指南](../README.md)

本指南为一个 NeoForge 1.21.1 模组接入 CompixelUI，并打开第一个界面。其他 Minecraft 版本的步骤相同，差异见[兼容性](compatibility.md)。

## 1. 添加依赖

在模组的 `build.gradle` 中：

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.4.10'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.4.10'
}

repositories {
    maven {
        url = 'https://maven.wintercogs.com/releases'
        content { includeGroup 'dev.compixel' }
    }
}

dependencies {
    compileOnly "dev.compixel:compixel-neoforge-1.21.1-with-kotlin:${compixel_version}"
    localRuntime "dev.compixel:compixel-neoforge-1.21.1-with-kotlin:${compixel_version}"
}

kotlin { jvmToolchain(21) }
```

在 `gradle.properties` 中，填入 [Releases](https://github.com/Frostbite-time/CompixelUI/releases) 上的最新版本：

```properties
compixel_version=<version>
kotlin.stdlib.default.dependency=false
```

`-with-kotlin` 坐标会一并带上 Kotlin 库。如果你的模组本来就依赖 Kotlin for Forge，两行都改用 `compixel-neoforge-1.21.1`，由 KFF 提供 Kotlin。

CompixelUI 作为独立模组安装，不要用 Jar-in-Jar 打进你的 JAR。

## 2. 声明依赖

在 `src/main/resources/META-INF/neoforge.mods.toml` 中，换成你的模组 ID，版本与上面相同：

```toml
[[dependencies.examplemod]]
modId = "compixel"
type = "required"
versionRange = "[<version>]"
ordering = "AFTER"
side = "CLIENT"
```

如果用到[菜单同步](menu-sync.md)，它也在服务端运行，请改为 `side = "BOTH"`。

## 3. 打开界面

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.compixel.forge.ComposeScreen
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.layout.OreScreen
import net.minecraft.network.chat.Component

class CounterScreen : ComposeScreen<Int, Unit>(Component.literal("计数器")) {
    private var count = 0

    override fun snapshot() = count

    override fun handle(action: Unit) {
        count++
    }

    @Composable
    override fun Content(state: Int) {
        OreScreen("计数器", maxWidth = 160.dp, maxHeight = 78.dp) {
            OreText("已点击 $state 次")
            OreButton("点我", onClick = { send(Unit) })
        }
    }
}
```

![点击三次后的计数器界面](../assets/counter-zh-CN.png)

在客户端代码中用 `Minecraft.getInstance().setScreen(CounterScreen())` 打开它，例如在按键绑定的处理函数里。`ComposeScreen` 就是普通的 Minecraft 界面，`OreScreen` 负责绘制面板和标题。界面代码放在仅客户端加载的类里，专用服务器就不会加载它。

界面的数据留在游戏线程，Compose 在自己的线程上绘制。`ComposeScreen<S, A>` 通过三个需要重写的成员把两者连起来：`snapshot` 把数据读成类型为 `S` 的不可变值，`Content` 绘制最新快照，`handle` 执行按钮通过 `send` 发来的、类型为 `A` 的动作。这里计数保存在界面里，每次点击就是一个动作。

## 4. 显示游戏数据

`snapshot` 和 `handle` 运行在游戏线程，可以使用玩家、物品堆等游戏对象。下面的界面显示玩家主手中的物品，点击按钮时丢出一个：

```kotlin
data class HeldItem(val name: String, val count: Int)

class HandScreen : ComposeScreen<HeldItem, Unit>(Component.literal("手持物品")) {
    override fun snapshot(): HeldItem {
        val stack = Minecraft.getInstance().player?.mainHandItem ?: ItemStack.EMPTY
        return HeldItem(stack.hoverName.string, stack.count)
    }

    override fun handle(action: Unit) {
        Minecraft.getInstance().player?.drop(false)
    }

    @Composable
    override fun Content(state: HeldItem) {
        OreScreen("手持物品", maxWidth = 180.dp, maxHeight = 84.dp) {
            OreText("${state.name} × ${state.count}")
            OreButton("丢出一个", onClick = { send(Unit) })
        }
    }
}
```

用 `Minecraft.getInstance().setScreen(HandScreen())` 打开它。

| 成员 | 何时运行 |
| --- | --- |
| `snapshot()` | 在游戏线程调用：界面打开时、每次发出动作的输入事件之后，以及每刻 |
| `handle(action)` | 在游戏线程逐个处理动作，先于下一份快照 |
| `Content(state)` | 在 Compose 线程，参数是最新快照 |
| `send(action)` | 任意线程，通常在 UI 回调中。界面已关闭或已有 64 个动作排队时返回 `false`。 |

点击、按键或输入文字时发出的动作，会在这次事件返回前处理完并取得新快照，和原版按钮一样立即生效，下一帧就能看到结果。其他时候发出的动作（例如在协程里）等到下一刻处理。由于 `snapshot()` 每刻可能调用不止一次，它只应读取游戏状态。

界面在第一帧之前取得第一份快照。界面关闭时，尚未处理的动作会被丢弃；再次打开时，从新的快照开始。在按钮中调用 `requestClose()` 可以关闭界面，界面会在这次点击返回前关闭。

`snapshot()` 应返回数据类等不可变值：快照没有变化时不会重绘。没有游戏状态的界面继承 `ComposeScreen<Unit, Nothing>`，写 `override fun snapshot() {}` 和 `override fun handle(action: Nothing) {}`。`ComposeMenuScreen`、`ComposeInventoryScreen` 和 `ComposeHudLayer` 以同样的方式用于[菜单与容器界面](inventory.md#显示菜单状态)和 [HUD 层](hud.md)。

`Content` 中只使用 `state`、`send` 和构造时准备好的值。不要在可组合项里访问玩家、物品堆等游戏对象，也不要让 Compose 等待游戏线程。

## 5. 发布

玩家把 CompixelUI 和你的模组装在一起。每个 Minecraft 版本有两个文件，玩家选其一：

| 文件 | 适用于 |
| --- | --- |
| `compixel-neoforge-1.21.1-…-with-kotlin.jar` | 所有玩家，已包含 Kotlin |
| `compixel-neoforge-1.21.1-….jar` | 已安装 Kotlin for Forge 的玩家 |

两个文件都可以在 [Releases](https://github.com/Frostbite-time/CompixelUI/releases) 页面下载。

## 下一步

- [Ore UI](ore-ui.md)：所有控件及示例
- [物品与提示](items.md)：在界面中显示真实物品
- [容器界面](inventory.md)：带槽位的菜单
- [进出场动画](transitions.md)：为界面添加入场与退场动画
- [HUD 层](hud.md)：游戏画面上的 Compose 内容
- [菜单同步](menu-sync.md)：服务端状态与客户端请求
