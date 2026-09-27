# Container screens

[简体中文](../zh-CN/inventory.md) · [All guides](../README.md)

`ComposeInventoryScreen` lays out a menu's real slots with Compose. Everything else stays native: clicking, dragging, double-click collecting, shift-clicking, the carried stack, slot tooltips, and the hooks that recipe viewers and other mods rely on.

![A chest laid out with Compose, showing the tooltip of an enchanted book](../assets/storage-en.png)

## Lay out a menu

This screen is for a menu with 27 storage slots followed by the player's inventory, like a chest:

```kotlin
fun storageScreen(menu: StorageMenu, inventory: Inventory, title: Component): ComposeInventoryScreen<StorageMenu> {
    val caption = title.string
    return ComposeInventoryScreen(menu, title) { slots ->
        OreScreen(caption, maxWidth = 176.dp, maxHeight = 190.dp, panelModifier = slots.areaModifier()) {
            SlotGrid(slots, 0 until 27)
            OreText("Inventory")
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

Register it like any other menu screen, from your client mod constructor:

```kotlin
modBus.addListener { event: RegisterMenuScreensEvent ->
    event.register(ModMenus.STORAGE.get(), ::storageScreen)
}
```

- `slots.Slot(id)` places the menu slot with that index. Place each slot once; slots you leave out are hidden.
- `slots.areaModifier()` marks the area other mods treat as the container, for example to place recipe viewer panels beside it.
- Read the title and other game objects before composition, as `caption` does here.

On Forge 1.20.1, register the screen with `MenuScreens.register` inside `FMLClientSetupEvent.enqueueWork`.

## Choose a screen class

| Class | For |
| --- | --- |
| `ComposeScreen` | Screens without a menu |
| `ComposeMenuScreen` | Server menus without slots |
| `ComposeInventoryScreen` | Menus with slots |
| `SlotBehaviorScreen` | Container screens you draw natively, with Compose MC's slot rules |

## Change what a click does

Slot rules are immutable `SlotBehavior` values. This one turns right-click into your own action instead of splitting a stack:

```java
SlotBehavior inspect = SlotBehavior.standard()
    .replaceClick(1, new SlotIntent.Local("example:inspect"));
```

Return it from your `MenuSlotAdapter`'s `behavior` hook, or override `slotBehavior` in a `SlotBehaviorScreen`, and handle the action in `localAction`. Other gestures stay as they are. A local action sends nothing to the server by itself.

`MenuSlotAdapter` also decides what each slot shows (`visual`), where dragging may go (`canDragTo`) and how clicks run (`execute`). The default, `VanillaMenuSlotAdapter`, does what Minecraft does.

## Shift-click routes

Declare where shift-clicked items go, then let `quickMoveStack` use the routes:

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

Ranges exclude their end, and `true` fills that group from its last slot. Stacks merge into matching stacks before filling empty slots, and every slot's limits still apply. Result slots, such as crafting output, need their own handling.

## Overlays and other screens

- Call `slots.Interaction(enabled = false)` while a dialog or window should block slot clicks.
- Override `inventoryTick()` to update a `UiBinding` each tick.
- When a recipe viewer opens its own screen over yours, the menu stays open. Keep your bindings until the menu itself closes.
