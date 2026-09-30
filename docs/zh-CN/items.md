# 物品与提示

[English](../en/items.md) · [全部指南](../README.md)

CompixelUI 能在 Compose 中绘制真实的 Minecraft 物品：模型、动画和附魔光效与物品栏里完全一致，还能显示游戏原版的物品提示。

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

这些类型位于 `dev.compixel.forge.item`。

## 动画

图标会自动跟随游戏：附魔光效、动态纹理、指南针、时钟和冷却遮罩。需要不同行为时，给 `snapshot` 传入刷新策略：

| 策略 | 何时重绘 |
| --- | --- |
| `IconRefresh.AUTO`（默认） | 物品有动画（如附魔光效、动态纹理）时每个游戏刻；否则在外观变化时 |
| `IconRefresh.GAME_TICK` | 每个游戏刻 |
| `IconRefresh.FRAME` | 每一帧 |
| `IconRefresh.every(ms)` | 固定间隔，16 到 60,000 毫秒 |
| `IconRefresh.STATIC` | 从不，保留第一帧画面 |

有动画的图标每个游戏刻重绘一次，与 Minecraft 播放纹理动画的频率相同。如果某个物品的自定义渲染器在模型不变的情况下播放动画，请使用 `GAME_TICK`；只有需要比这更快变化的绘制才使用 `FRAME`。

## 自绘图标

`ItemIcon.drawn` 把 16×16 范围内的任意 `GuiGraphics` 绘制变成图标，适合流体、能量等不是物品的东西：

```kotlin
val water = ItemIcon.drawn("水", { graphics ->
    graphics.fill(0, 0, 16, 16, 0xFF3F76E4.toInt())
})
```

绘制在渲染线程执行，默认每个游戏刻重绘一次，也可以传入其他策略。自绘图标没有物品提示，可以改用 [OreTooltip](ore-ui.md#提示) 包裹。

## 大量物品

界面上的每个图标都会显示，不论数量多少。图标绘制在图集页中，每页最多 64 个。一页只重绘到期的图标，所以一个有动画的物品不会连带重绘旁边的图标。显示的图标增多时会添加新页，页中图标都不再使用后，该页随之释放。

创建 `ComposeScreen`、`ComposeMenuScreen`、`ComposeInventoryScreen` 或 `ComposeHudLayer` 时，可以通过 `nativeItemOptions` 为该界面或 HUD 层单独调整：

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

`NativeItemOptions` 位于 `dev.compixel.forge.item`。

| 参数 | 默认值 | 范围 | 含义 |
| --- | --- | --- | --- |
| `cacheCapacity` | 128；容器界面为 256 | 1–1024 | 屏幕上的图标少于此数量时保留的图标数。滚出视野的图标在容量内继续缓存，滚回时无需重绘 |
| `preparationsPerFrame` | 64 | 1–64 | 每帧最多绘制的图标数量，也是一个图集页容纳的图标数量 |
| `imageSize` | 16 dp 图标在屏幕上的像素（16 × GUI 缩放） | 16–256 | 每个 16×16 图标绘制时使用的像素尺寸。默认跟随 GUI 缩放，以 16 dp 显示的图标与原版物品渲染逐像素一致；指定固定尺寸会对图标重新采样 |

界面显示的图标多于 `preparationsPerFrame` 时，会在几帧内陆续显示完整。一页 64 个图标在 GUI 缩放为 4 时约占 4 MB 显存，缩放为 2 时约 1 MB，缩放为 6 时约 9 MB。重复使用同一个 `ItemIcon` 句柄共用一份图像；分别创建的句柄即使物品相同，也各占一份。

## 另见

- [容器界面](inventory.md)会自动为真实槽位显示物品、数量和提示。
- [HUD 层](hud.md)同样可以显示物品图标，但提示需要在界面中使用。
