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
| `NativeRefresh.AUTO`（默认） | 物品有动画（如附魔光效、动态纹理）时每个游戏刻；否则在外观变化时 |
| `NativeRefresh.GAME_TICK` | 每个游戏刻 |
| `NativeRefresh.FRAME` | 每一帧 |
| `NativeRefresh.every(ms)` | 固定间隔，16 到 60,000 毫秒 |
| `NativeRefresh.STATIC` | 从不，保留第一帧画面 |

有动画的图标每个游戏刻重绘一次，与 Minecraft 播放纹理动画的频率相同。如果某个物品的自定义渲染器在模型不变的情况下播放动画，请使用 `GAME_TICK`；只有需要比这更快变化的绘制才使用 `FRAME`。

`NativeRefresh` 位于 `dev.compixel.forge.drawing`，供物品图标和矩形绘制共用。

## 自绘图标

`ItemIcon.drawn` 把 16×16 范围内的任意 `GuiGraphics` 绘制变成图标，适合流体、能量等不是物品的东西：

```kotlin
val water = ItemIcon.drawn("水", { graphics ->
    graphics.fill(0, 0, 16, 16, 0xFF3F76E4.toInt())
})
```

绘制在渲染线程执行，默认每个游戏刻重绘一次，也可以传入其他策略。自绘图标没有物品提示，可以改用 [OreTooltip](ore-ui.md#提示) 包裹。

## 矩形原生绘制

面板、预览或其他超过 16×16 图标范围的原生内容使用 `NativeDrawing`。在游戏线程创建句柄，再交给 Compose 重复使用：

```kotlin
import dev.compixel.forge.drawing.NativeDrawing
import dev.compixel.forge.drawing.NativeRefresh
import dev.compixel.forge.drawing.MinecraftNativeDrawing

val panel = NativeDrawing.create("原生面板", { context ->
    context.graphics.fill(0, 0, context.width, context.height, 0xFF203040.toInt())
    context.graphics.fill(4, 4, context.width - 4, 12, 0xFF80D4C0.toInt())
}, NativeRefresh.STATIC)

// 在 CompixelUI 界面或 HUD 中：
MinecraftNativeDrawing(panel, Modifier.size(180.dp, 48.dp))
```

回调在游戏/渲染线程执行。`context.graphics` 是对应版本的原生 `GuiGraphics`（1.20.1/1.21.1）或 `GuiGraphicsExtractor`（26.x）。坐标从组件左上角开始，`width` 和 `height` 是向上取整以覆盖图像的局部 GUI 尺寸，`pixelWidth` 和 `pixelHeight` 是精确的物理像素尺寸。

组件没有固有尺寸，需要指定尺寸或在有界布局中填充。可以正常使用 Compose 的裁剪、透明度和变换修饰符。原生画面在这些显示变换之前捕获，输入仍由 Compose 处理。原生 scissor 使用局部目标坐标，超出图像边缘的内容会被裁掉。

回调中不要读取 Compose 状态，也不要保存 graphics 对象。通过重复使用的句柄传递不可变快照，快照改变时替换句柄；动画回调可以读取归游戏线程所有的状态。默认刷新策略是 `NativeRefresh.GAME_TICK`，自绘内容的 `AUTO` 也表示每个游戏刻刷新一次。仅显示中的内容会在绘制预算内刷新。

四种界面/HUD 宿主均提供 `nativeDrawingOptions = NativeDrawingOptions(cacheCapacity = 0, preparationsPerFrame = 4)`。缓存容量（0–128）控制可见内容较少时保留的图像数，默认释放隐藏的目标；每帧准备数（1–64）限制所有独立矩形目标的原生绘制次数。同一尺寸重复使用同一句柄会共用图像，不同尺寸各自分配目标。尺寸连续变化时先显示最接近的已有图像，稳定后再绘制新尺寸。资源重载和 GUI 缩放变化也会刷新静态内容。

物品图标继续使用紧凑的图集和原生物品处理。矩形使用独立目标，保证原生裁剪和依赖视口的绘制正确；两者共用底层图像调度、Compose 发布和渲染器回收机制。

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
| `imageSize` | 图标布局所占的像素 | 16–256 | 每个 16×16 图标绘制时使用的像素尺寸。默认按图标布局所占的像素绘制，因此任何尺寸都逐像素显示，16 dp 的图标与原版物品渲染完全一致。同一图标以两种尺寸显示时分别绘制；尺寸持续变化时（例如动画中），先显示最接近的已绘制尺寸，稳定后再绘制新尺寸。指定固定尺寸时，每个图标只绘制一次，并在屏幕上重新采样 |

界面显示的图标多于 `preparationsPerFrame` 时，会在几帧内陆续显示完整。每页只容纳同一尺寸的图标：以 16 dp 显示的 64 个图标在 GUI 缩放为 4 时约占 4 MB 显存，缩放为 2 时约 1 MB，缩放为 6 时约 9 MB，更大的图标按尺寸的平方占用更多。重复使用同一个 `ItemIcon` 句柄共用一份图像；分别创建的句柄即使物品相同，也各占一份。

## 另见

- [容器界面](inventory.md)会自动为真实槽位显示物品、数量和提示。
- [HUD 层](hud.md)同样可以显示物品图标，但提示需要在界面中使用。
