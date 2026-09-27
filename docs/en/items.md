# Items and tooltips

[简体中文](../zh-CN/items.md) · [All guides](../README.md)

Compose MC draws real Minecraft items inside Compose, with the same models, animations and enchantment glint as an inventory, plus the game's own item tooltips.

![An item catalog with the tooltip of an enchanted sword](../assets/items-en.png)

## Show items

Take a snapshot of each stack on the game thread with `ItemIcon.snapshot`, then hand the icons to Compose:

```kotlin
fun openItemCatalog(stacks: List<ItemStack>) {
    val icons = stacks.map { ItemIcon.snapshot(it) }
    Minecraft.getInstance().setScreen(ComposeScreen(Component.literal("Item catalog")) {
        OreScreen("Item catalog", maxWidth = 176.dp, maxHeight = 110.dp) {
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

- `MinecraftItemIcon` behaves like any other composable: size, clip, rotate or fade it.
- `MinecraftItemTooltip` shows the tooltip after the pointer rests for 500 ms, drawn by Minecraft itself, so lines added by other mods appear too.
- An icon holds its own copy of the stack. Reuse it while the stack stays the same, and take a new snapshot when it changes.

The types are in `dev.composemc.forge.item`.

## Animation

Icons follow the game on their own: enchantment glint, animated textures, compasses, clocks and cooldowns. Pass a refresh policy to `snapshot` to change that:

| Policy | Redraws the icon |
| --- | --- |
| `IconRefresh.AUTO` (default) | When the item's look changes |
| `IconRefresh.GAME_TICK` | Every game tick |
| `IconRefresh.FRAME` | Every frame |
| `IconRefresh.every(ms)` | At a fixed interval, 16 to 60,000 ms |
| `IconRefresh.STATIC` | Never; the first image stays |

Choose `GAME_TICK` or `FRAME` for items whose custom renderer animates without changing its model.

## Draw your own icons

`ItemIcon.drawn` turns any `GuiGraphics` drawing inside a 16×16 area into an icon, for things that aren't items, such as fluids or energy:

```kotlin
val water = ItemIcon.drawn("Water", { graphics ->
    graphics.fill(0, 0, 16, 16, 0xFF3F76E4.toInt())
})
```

The drawing runs on the render thread and repeats every game tick unless you pass another policy. Drawn icons have no item tooltip; wrap them in an [OreTooltip](ore-ui.md#tooltips) instead.

## Large grids

A screen keeps up to 128 prepared icons by default; `ComposeInventoryScreen` defaults to 256. Set `nativeItemOptions` when creating a `ComposeScreen`, `ComposeMenuScreen`, `ComposeInventoryScreen` or `ComposeHudLayer` to choose its own cache capacity:

```kotlin
ComposeScreen(title, nativeItemOptions = NativeItemOptions(cacheCapacity = 512)) { … }

ComposeInventoryScreen(
    menu,
    title,
    nativeItemOptions = NativeItemOptions(cacheCapacity = 1024),
) { slots ->
    // Lay out the menu's slots here.
}
```

`NativeItemOptions` is in `dev.composemc.forge.item`.

| Option | Default | Range | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | 128; 256 for inventory screens | 1–1024 | Total number of cached icon handles across all atlas pages in this screen or HUD layer |
| `preparationsPerFrame` | 64 | 1–64 | Maximum number of icons prepared in one frame |
| `imageSize` | 64 | 16–256 | Pixel size used to prepare each 16×16 icon |

Increasing `cacheCapacity` keeps the per-frame preparation limit unchanged. Pages are prepared over several frames and consume more GPU memory as the cache fills. Size the cache for every simultaneously active icon, including player inventory, crafting and carried items, and leave room for lazy-layout prefetch. Reusing the same `ItemIcon` handle shares one cache entry; separately created handles use separate entries even for identical stacks. When all cached entries are still active, extra icons remain pending until an entry becomes available; waiting alone does not make an oversized grid complete.

## See also

- [Container screens](inventory.md) show items, counts and tooltips for real menu slots on their own.
- [HUD layers](hud.md) can show item icons too; tooltips need a screen.
