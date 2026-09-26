# HUD 层

[English](../en/hud.md) · [文档目录](../README.md)

`ComposeHudLayer` 把 Compose 内容作为普通的 HUD 层绘制。所有目标都在 `dev.composemc.forge` 中以相同的构造参数提供它。它实现的是各加载器自己的层类型，因此和其他 HUD 层一样，通过加载器注册并决定顺序。本页示例基于 NeoForge 1.21.1。

## 注册 HUD 层

在仅客户端的代码中注册，例如注册屏幕的客户端入口。绑定负责把游戏状态送进 HUD 层，做法与[快速开始](getting-started.md#4-连接游戏状态)相同：

```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composemc.forge.ComposeHudLayer
import dev.composemc.host.UiBinding
import dev.composemc.ui.ore.display.OreText
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge

object HealthHud {
    private lateinit var health: UiBinding<Int, Nothing>

    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::registerLayer)
        NeoForge.EVENT_BUS.addListener(::tick)
    }

    // 游戏启动时在客户端线程上执行。
    private fun registerLayer(event: RegisterGuiLayersEvent) {
        health = UiBinding(0)
        event.registerAbove(
            VanillaGuiLayers.HOTBAR,
            ResourceLocation.fromNamespaceAndPath("examplemod", "health"),
            ComposeHudLayer { OreText("Health: ${health.value}", Modifier.padding(8.dp)) },
        )
    }

    private fun tick(event: ClientTickEvent.Post) {
        Minecraft.getInstance().player?.let { health.update(it.health.toInt()) }
    }
}
```

| 目标 | 注册事件 | 层类型 | 标识与锚点 |
| --- | --- | --- | --- |
| Forge 1.20.1 | `RegisterGuiOverlaysEvent` | `IGuiOverlay` | 本模组命名空间下的 `String`；`VanillaGuiOverlay.HOTBAR.id()` |
| NeoForge 1.21.1 | `RegisterGuiLayersEvent` | `LayeredDraw.Layer` | `ResourceLocation`；`VanillaGuiLayers` |
| NeoForge 26.x | `RegisterGuiLayersEvent` | `GuiLayer` | `Identifier`；`VanillaGuiLayers` |

## 行为

- **顺序。** HUD 层在注册的位置绘制于游戏画面之上。打开的屏幕绘制在它上方，它在屏幕下方继续绘制，与原版 HUD 元素一致。
- **随 HUD 隐藏。** 按 F1 隐藏原版 HUD 时，加载器仍可能绘制已注册的层，所以 HUD 层自行检查，HUD 隐藏期间不绘制任何内容。
- **不接收输入。** 指针、按键和文本都留给游戏或打开的屏幕。内容永远没有窗口焦点：文本框无法编辑，Ore 菜单和提示不会打开，`MinecraftItemTooltip` 也不会显示。需要交互时请打开屏幕。
- **原生物品。** `MinecraftItemIcon` 的用法与屏幕中相同。请在客户端线程创建 `ItemIcon` 快照，见[原生内容](native-content.md)。
- **生命周期。** 会话在进入世界后绘制第一帧时开启，在窗口缩放、GUI 缩放变化和资源重载中保留状态，玩家离开世界时关闭。`close()` 可以提前释放，例如模组关闭 HUD 时。下一次绘制会开启新会话，`remember` 的状态随之重置。

## 开销

即使内容没有变化，每次绘制也有一次 Compose 线程往返和一次整窗合成；未变化的内容复用保留的帧，变化的内容会重新录制并重绘这一帧。每个 HUD 层都有独立的会话、窗口大小的绘制表面和 Skia 上下文，因此建议每个模组只注册一个层、把所有元素放在其中，并避免永不停止的动画。基准测试包含静态和动画两种 HUD，见[构建与测试](build-and-test.md#基准测量规程)。

`rendererStatistics`、`nativeItemStatistics`，以及开启 `-Dcomposemc.profile=true` 后的 `frameProfiler`，请在客户端线程读取。
