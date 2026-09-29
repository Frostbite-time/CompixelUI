# 容器界面

[English](../en/inventory.md) · [全部指南](../README.md)

`ComposeInventoryScreen` 用 Compose 排布菜单的真实槽位，其余一切保持原版：点击、拖动、双击收集、Shift 点击、手持物品、槽位提示，以及配方查看器等模组依赖的钩子。

![用 Compose 排布的箱子界面，显示一本附魔书的提示](../assets/storage-zh-CN.png)

## 排布菜单

下面的界面对应一个"27 个储物槽 + 玩家物品栏"的菜单，和箱子一样：

```kotlin
fun storageScreen(menu: StorageMenu, inventory: Inventory, title: Component): ComposeInventoryScreen<StorageMenu> {
    val caption = title.string
    return ComposeInventoryScreen(menu, title) { slots ->
        OreScreen(caption, maxWidth = 176.dp, maxHeight = 190.dp, panelModifier = slots.areaModifier()) {
            SlotGrid(slots, 0 until 27)
            OreText("物品栏")
            SlotGrid(slots, 27 until 54)
            SlotGrid(slots, 54 until 63)
        }
    }
}

@Composable
fun SlotGrid(slots: ComposeMenuSlots<*>, ids: IntRange) {
    Column {
        for (row in ids.chunked(9)) Row { for (id in row) slots.Slot(id) }
    }
}
```

像注册其他菜单界面一样，在客户端模组构造函数中注册它：

```kotlin
modBus.addListener { event: RegisterMenuScreensEvent ->
    event.register(ModMenus.STORAGE.get(), ::storageScreen)
}
```

- `slots.Slot(id)` 放置对应序号的菜单槽位。每个槽位只放一次，没放的槽位会被隐藏。
- `slots.areaModifier()` 标出其他模组眼中的容器区域，例如配方查看器据此把面板摆在旁边。
- 标题等游戏对象请在组合之前读取，就像这里的 `caption`。

Forge 1.20.1 请在 `FMLClientSetupEvent.enqueueWork` 中用 `MenuScreens.register` 注册。

大型容器可以给 `ComposeInventoryScreen` 传入 `nativeItemOptions = NativeItemOptions(cacheCapacity = 1024)`。默认容量为 256 个图标句柄，覆盖整个界面，包括玩家背包和合成区。各参数及每帧准备上限见[大量物品](items.md#大量物品)。

## 选择界面类

| 类 | 适用于 |
| --- | --- |
| `ComposeScreen` | 没有菜单的界面 |
| `ComposeMenuScreen` | 没有槽位的服务端菜单 |
| `ComposeInventoryScreen` | 带槽位的菜单 |
| `SlotBehaviorScreen` | 原生绘制、但使用 CompixelUI 槽位规则的容器界面 |

## 修改点击行为

槽位规则是不可变的 `SlotBehavior`。下面的规则把右键改成你自己的动作，而不是拆分物品堆：

```java
SlotBehavior inspect = SlotBehavior.standard()
    .replaceClick(1, new SlotIntent.Local("example:inspect"));
```

在 `MenuSlotAdapter` 的 `behavior` 中返回它（或在 `SlotBehaviorScreen` 中重写 `slotBehavior`），再在 `localAction` 中处理这个动作。其他操作保持不变。本地动作本身不会向服务端发送任何内容。

`MenuSlotAdapter` 还决定每个槽位显示什么（`visual`）、拖动能经过哪些槽位（`canDragTo`）以及点击如何执行（`execute`）。默认的 `VanillaMenuSlotAdapter` 与 Minecraft 的行为一致。

## Shift 点击路线

声明 Shift 点击时物品的去向，再让 `quickMoveStack` 使用这些路线：

```java
SlotTransferRoutes routes = SlotTransferRoutes.builder(63)
    .group("storage", 0, 27)
    .group("inventory", 27, 54)
    .group("hotbar", 54, 63, true)
    .route("storage", "hotbar", "inventory")
    .route("inventory", "storage")
    .route("hotbar", "storage")
    .build();

@Override
public ItemStack quickMoveStack(Player player, int slot) {
    return NativeSlotTransfers.quickMove(this, player, slot, routes);
}
```

范围不含结束值；`true` 表示从该组最后一个槽位开始填充。物品会先合并到相同的物品堆，再填入空槽，每个槽位的上限照常生效。合成输出等结果槽需要单独处理。

## 浮层与其他界面

- 对话框或窗口需要挡住槽位点击时，调用 `slots.Interaction(enabled = false)`。
- 重写 `inventoryTick()`，可以每刻更新 `UiBinding`。
- 配方查看器打开自己的界面时，菜单仍保持打开。请保留你的绑定，直到菜单本身关闭。
