# HUD 层

[English](../en/hud.md) · [全部指南](../README.md)

`ComposeHudLayer` 在游戏画面上绘制 Compose 内容。它就是普通的 HUD 层：通过加载器注册，与原版 HUD 叠放在一起。

![游戏画面左上角的钻石任务面板](../assets/hud-zh-CN.png)

## 注册 HUD 层

这个 HUD 层统计玩家物品栏中的钻石：

```kotlin
object QuestHud {
    private lateinit var diamonds: UiBinding<Int, Nothing>

    fun register(modBus: IEventBus) {
        modBus.addListener(::registerLayer)
        NeoForge.EVENT_BUS.addListener(::tick)
    }

    private fun registerLayer(event: RegisterGuiLayersEvent) {
        diamonds = UiBinding(0)
        val diamond = ItemIcon.snapshot(ItemStack(Items.DIAMOND))
        event.registerAbove(
            VanillaGuiLayers.HOTBAR,
            ResourceLocation.fromNamespaceAndPath("examplemod", "quest"),
            ComposeHudLayer { QuestPanel(diamond, diamonds.value) },
        )
    }

    private fun tick(event: ClientTickEvent.Post) {
        Minecraft.getInstance().player?.let { diamonds.update(it.inventory.countItem(Items.DIAMOND)) }
    }
}

@Composable
fun QuestPanel(diamond: ItemIcon, found: Int) {
    OreSurface(Modifier.padding(8.dp).width(128.dp)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MinecraftItemIcon(diamond)
                OreText("钻石 $found / 16")
            }
            OreProgressBar(found / 16f)
        }
    }
}
```

在客户端模组构造函数中调用 `QuestHud.register(modBus)`。

## 表现

- HUD 层绘制在注册时指定的位置，位于所有打开的界面之下。
- 玩家按 F1 隐藏 HUD 时，它一起隐藏。
- 它不接收输入：点击、按键和文字都交给游戏，提示也不会弹出。需要交互的内容请放在界面里。
- 它在进入世界后的第一帧启动，离开世界时停止。调用 `close()` 可以提前停止；下次启动时 `remember` 的状态会重新开始。

## 其他版本

| Minecraft | 注册事件 | 层类型 |
| --- | --- | --- |
| 1.20.1（Forge） | `RegisterGuiOverlaysEvent` | `IGuiOverlay`，使用字符串 ID 和 `VanillaGuiOverlay` 锚点 |
| 1.21.1 | `RegisterGuiLayersEvent` | `LayeredDraw.Layer`，使用 `ResourceLocation` |
| 26.x | `RegisterGuiLayersEvent` | `GuiLayer`，使用 `Identifier` |

各版本的 `ComposeHudLayer` 构造方式相同。

## 保持轻量

每个 HUD 层都有独立的 Compose 会话和窗口大小的绘制表面，HUD 可见时每帧都会合成。请把一个模组的所有 HUD 元素放进同一层，并避免永不停止的动画。
