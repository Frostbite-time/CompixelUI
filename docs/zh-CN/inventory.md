# 容器与槽位策略

[English](../en/inventory.md) · [文档目录](../README.md)

使用 `NeoForgeComposeInventoryScreen` 通过 Compose 排列真实菜单槽位，同时保留原生容器屏幕及输入、渲染钩子。Forge 1.20.1 在 `dev.composemc.forge` 中提供 `ForgeComposeInventoryScreen`。

![带原生物品的槽位外观](../assets/native-slots.png)

*在打包后的 1.21.1 OpenGL 预览中获取的槽位外观截图，预览语言为英文。这里展示视觉状态，下方示例用于连接真实菜单。*

## 选择宿主

| 宿主 | 用途 |
| --- | --- |
| `NeoForgeComposeScreen` | 无原生菜单生命周期的客户端 UI |
| `NeoForgeComposeMenuScreen<M>` | 服务端驱动且**没有槽位**的菜单 |
| `NeoForgeComposeInventoryScreen<M>` | 包含真实槽位和容器手势的菜单 |
| `NeoForgeSlotBehaviorScreen<M>` | 保留原生绘制、采用共享槽位策略的容器 |

无槽位菜单宿主会拒绝包含槽位的菜单。NeoForge 使用 `RegisterMenuScreensEvent` 注册菜单屏幕；Forge 1.20.1 在客户端初始化的 `enqueueWork` 中调用 `MenuScreens.register`。

## 布局菜单

从客户端菜单屏幕注册中调用这个工厂函数。它在组合前捕获 ID 和标题，适用于固定的小型物品栏；更大的布局应对可见槽位使用滚动或分页。

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composemc.neoforge.NeoForgeComposeInventoryScreen
import dev.composemc.ui.ore.layout.OreScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu

fun <M : AbstractContainerMenu> inventoryScreen(menu: M, title: Component): NeoForgeComposeInventoryScreen<M> {
    val ids = menu.slots.indices.toList()
    val caption = title.string
    return NeoForgeComposeInventoryScreen(menu, title, content = { slots ->
        OreScreen(caption, panelModifier = slots.areaModifier()) {
            ids.chunked(9).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { id -> slots.Slot(id, Modifier.size(18.dp)) }
                }
            }
        }
    })
}
```

槽位 ID 是原生菜单/协议 ID，不是显示行号。不要同时放置两次相同槽位 ID。`areaModifier()` 报告容器区域；`bounds(id)` 和 `areaBounds()` 为集成提供经过裁剪的 GUI 单位矩形，未放置的槽位没有边界。GUI 单位与帧缓冲像素属于不同坐标空间。

## 保留原生行为

宿主保留原版点击、拖拽、双击收集、交换、丢弃、鼠标携带堆绘制及容器钩子。`VanillaMenuSlotAdapter` 为普通物品创建快照，通过 Minecraft 原有点击与预测路径执行。空槽位保留原生背景图标提示。

使用 `inventoryTick()` 在游戏线程发布快照或处理 `UiBinding` 动作。配方查看器临时替换屏幕、且同一菜单仍然有效时，应保留绑定。访问期间可以释放渲染资源；菜单最终关闭或替换时再释放业务绑定。

弹层需要阻止原生槽位输入时，在组合中调用 `slots.Interaction(enabled = !overlayBlocksInventory)`。禁用、缩放或失焦会取消未完成的输入捕获。文本编辑优先于容器快捷键；Compose 屏幕宿主也提供 `hasTextInputFocus`。

同步动作分片仍在发送队列中时，内置容器宿主暂停原生物品栏手势，以保持与原版数据包的顺序。自定义执行器也应遵守 `menuSync().isSendingAction()`，见[菜单同步](menu-sync.md)。

## 自定义槽位

`MenuSlotAdapter` 提供 `visual`、`behavior`、`canDragTo`、`execute`、`localAction` 和 `tooltip` 钩子。这些钩子负责访问实时游戏对象，组合函数只接收快照。`MenuSlotVisual` 包含物品句柄、完整/紧凑数量文字和标记样式。仅数量变化时可复用图标。自定义绘制资源需要自定义提示钩子。

例如，以下不可变策略将右键点击替换为本地动作：

```java
SlotBehavior context = SlotBehavior.standard()
    .replaceClick(1, new SlotIntent.Local("example:context"));
```

导入 `dev.composemc.slots.SlotBehavior` 和 `SlotIntent`。在适配器的 `behavior` 钩子中返回该策略，或在 `NeoForgeSlotBehaviorScreen` 中覆盖 `slotBehavior` / `localSlotAction`。替换右键也会抑制右键拖拽阶段，其他标准手势继续可用。本地动作不会自动发包，也不会授予修改服务端物品栏的权限。

## 声明 Shift 点击路线

分配完槽位后再构建路线。下面定义存储区 ID 0–8、玩家背包 ID 9–35、快捷栏 ID 36–44：

```java
SlotTransferRoutes routes = SlotTransferRoutes.builder(45)
    .group("storage", 0, 9)
    .group("player", 9, 36)
    .group("hotbar", 36, 45, true)
    .route("player", "storage")
    .route("hotbar", "storage")
    .route("storage", "hotbar", "player")
    .build();
```

导入 `dev.composemc.slots.SlotTransferRoutes`。分组区间互不重叠，右端不包含；`true` 表示反向遍历目标。槽位分配变化后应重新构建。在菜单的 `quickMoveStack` 中，普通物品转移可委托给 `dev.composemc.neoforge.slots.NativeSlotTransfers.quickMove(this, player, slotId, routes)`。

执行器先合并相同物品堆，再填充空槽，遵守取出、放入和堆叠限制，并调用原生源槽位钩子。它不是回滚事务管理器。通用执行器会拒绝合成和交易结果槽。幽灵槽、虚拟资源和特殊合成结果需要消费者提供执行逻辑，仍可复用同一套路线声明。

Java 17 的 `slot-core` 只包含策略与路线。原生手势转换、网络包和执行逻辑属于版本适配器。用于槽位布局和绘制的少量版本专属访问转换见[架构说明](architecture.md)。
