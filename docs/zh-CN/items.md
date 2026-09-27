# 物品与提示

[English](../en/items.md) · [全部指南](../README.md)

Compose MC 能在 Compose 中绘制真实的 Minecraft 物品：模型、动画和附魔光效与物品栏里完全一致，还能显示游戏原版的物品提示。

![物品目录，显示一把附魔钻石剑的提示](../assets/items-zh-CN.png)

## 显示物品

在游戏线程用 `ItemIcon.snapshot` 为每个物品堆拍下快照，再把图标交给 Compose：

```kotlin
fun openItemCatalog(stacks: List<ItemStack>) {
    val icons = stacks.map { ItemIcon.snapshot(it) }
    Minecraft.getInstance().setScreen(ComposeScreen(Component.literal("物品目录")) {
        OreScreen("物品目录", maxWidth = 176.dp, maxHeight = 110.dp) {
            LazyVerticalGrid(GridCells.FixedSize(18.dp)) {
                items(icons) { icon ->
                    MinecraftItemTooltip(icon) {
                        OreSlot { MinecraftItemIcon(icon, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    })
}
```

- `MinecraftItemIcon` 和其他可组合项一样，可以调整大小、裁剪、旋转或设置透明度。
- 指针停留 500 毫秒后，`MinecraftItemTooltip` 显示物品提示。提示由 Minecraft 自己绘制，其他模组添加的提示行也会出现。
- 图标持有物品堆的一份副本。物品不变时重复使用同一个图标，变化后再拍新的快照。

这些类型位于 `dev.composemc.forge.item`。

## 动画

图标会自动跟随游戏：附魔光效、动态纹理、指南针、时钟和冷却遮罩。需要不同行为时，给 `snapshot` 传入刷新策略：

| 策略 | 何时重绘 |
| --- | --- |
| `IconRefresh.AUTO`（默认） | 物品外观变化时 |
| `IconRefresh.GAME_TICK` | 每个游戏刻 |
| `IconRefresh.FRAME` | 每一帧 |
| `IconRefresh.every(ms)` | 固定间隔，16 到 60,000 毫秒 |
| `IconRefresh.STATIC` | 从不，保留第一帧画面 |

如果某个物品的自定义渲染器在模型不变的情况下播放动画，请使用 `GAME_TICK` 或 `FRAME`。

## 自绘图标

`ItemIcon.drawn` 把 16×16 范围内的任意 `GuiGraphics` 绘制变成图标，适合流体、能量等不是物品的东西：

```kotlin
val water = ItemIcon.drawn("水", { graphics ->
    graphics.fill(0, 0, 16, 16, 0xFF3F76E4.toInt())
})
```

绘制在渲染线程执行，默认每个游戏刻重绘一次，也可以传入其他策略。自绘图标没有物品提示，可以改用 [OreTooltip](ore-ui.md#提示) 包裹。

## 大量物品

普通界面默认最多缓存 128 个图标，`ComposeInventoryScreen` 默认缓存 256 个。创建 `ComposeScreen`、`ComposeMenuScreen`、`ComposeInventoryScreen` 或 `ComposeHudLayer` 时，可以通过 `nativeItemOptions` 为它单独设置总缓存容量：

```kotlin
ComposeScreen(title, nativeItemOptions = NativeItemOptions(cacheCapacity = 512)) { … }

ComposeInventoryScreen(
    menu,
    title,
    nativeItemOptions = NativeItemOptions(cacheCapacity = 1024),
) { slots ->
    // 在这里排布菜单槽位。
}
```

`NativeItemOptions` 位于 `dev.composemc.forge.item`。

| 参数 | 默认值 | 范围 | 含义 |
| --- | --- | --- | --- |
| `cacheCapacity` | 128；容器界面为 256 | 1–1024 | 当前界面或 HUD 层所有图集页合计可缓存的图标句柄数量 |
| `preparationsPerFrame` | 64 | 1–64 | 每帧最多准备的图标数量 |
| `imageSize` | 64 | 16–256 | 每个 16×16 图标准备时使用的像素尺寸 |

增大 `cacheCapacity` 不会提高每帧准备上限。图集会分几帧准备，随着缓存填充占用更多显存。容量应覆盖同时处于使用状态的所有图标，包括玩家物品栏、合成区和手持物品，并为惰性布局预取留出余量。重复使用同一个 `ItemIcon` 句柄只占一个缓存条目；分别创建的句柄即使物品相同，也各占一个条目。如果缓存中的图标都还在使用，额外图标会一直等待，直到有条目可回收；超出容量的网格不会仅靠等待几帧就显示完整。

## 另见

- [容器界面](inventory.md)会自动为真实槽位显示物品、数量和提示。
- [HUD 层](hud.md)同样可以显示物品图标，但提示需要在界面中使用。
