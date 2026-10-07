# Items and tooltips

[简体中文](../zh-CN/items.md) · [All guides](../README.md)

CompixelUI draws real Minecraft items inside Compose, with the same models, animations and enchantment glint as an inventory, plus the game's own item tooltips.

![An item catalog with the tooltip of an enchanted sword](../assets/items-en.png)

## Show items

Take a snapshot of each stack on the game thread with `ItemIcon.snapshot`, then hand the icons to Compose:

```kotlin
class ItemCatalogScreen(stacks: List<ItemStack>) : ComposeScreen<Unit, Nothing>(Component.literal("Item catalog")) {
    private val icons = stacks.map { ItemIcon.snapshot(it) }

    override fun snapshot() {}

    override fun handle(action: Nothing) {}

    @Composable
    override fun Content(state: Unit) {
        OreScreen("Item catalog", maxWidth = 176.dp, maxHeight = 110.dp) {
            LazyVerticalGrid(GridCells.FixedSize(18.dp)) {
                items(icons) { icon ->
                    MinecraftItemTooltip(icon) {
                        OreSlot { MinecraftItemIcon(icon, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}
```

The screen is created on the game thread, so its constructor can take the snapshots. Icons that change with the game belong in the screen's state instead, taken in `snapshot()`.

- `MinecraftItemIcon` behaves like any other composable: size, clip, rotate or fade it.
- `MinecraftItemTooltip` shows the tooltip after the pointer rests for 500 ms, drawn by Minecraft itself, so lines added by other mods appear too.
- An icon holds its own copy of the stack. Reuse it while the stack stays the same, and take a new snapshot when it changes.

The types are in `dev.compixel.forge.item`.

## Animation

Icons follow the game on their own: enchantment glint, animated textures, compasses, clocks and cooldowns. Pass a refresh policy to `snapshot` to change that:

| Policy | Redraws the icon |
| --- | --- |
| `NativeRefresh.AUTO` (default) | Every game tick while the item animates, as with glint or animated textures; otherwise when its look changes |
| `NativeRefresh.GAME_TICK` | Every game tick |
| `NativeRefresh.FRAME` | Every frame |
| `NativeRefresh.every(ms)` | At a fixed interval, 16 to 60,000 ms |
| `NativeRefresh.STATIC` | Never; the first image stays |

Animated icons redraw once per game tick, the rate at which Minecraft animates its textures. Choose `GAME_TICK` for items whose custom renderer animates without changing its model, and `FRAME` only for drawings that must move faster.

`NativeRefresh` lives in `dev.compixel.forge.drawing` and is shared by item icons and rectangular drawings.

## Draw your own icons

`ItemIcon.drawn` turns any `GuiGraphics` drawing inside a 16×16 area into an icon, for things that aren't items, such as fluids or energy:

```kotlin
val water = ItemIcon.drawn("Water", { graphics ->
    graphics.fill(0, 0, 16, 16, 0xFF3F76E4.toInt())
})
```

The drawing runs on the render thread and repeats every game tick unless you pass another policy. Drawn icons have no item tooltip; wrap them in an [OreTooltip](ore-ui.md#tooltips) instead.

## Rectangular native drawing

Use `NativeDrawing` for a panel, preview or other native content larger than a 16×16 icon. Create its handle on the game thread and reuse it in Compose:

```kotlin
import dev.compixel.forge.drawing.NativeDrawing
import dev.compixel.forge.drawing.NativeRefresh
import dev.compixel.forge.drawing.MinecraftNativeDrawing

val panel = NativeDrawing.create("Native panel", { context ->
    context.graphics.fill(0, 0, context.width, context.height, 0xFF203040.toInt())
    context.graphics.fill(4, 4, context.width - 4, 12, 0xFF80D4C0.toInt())
}, NativeRefresh.STATIC)

// Inside a CompixelUI screen or HUD:
MinecraftNativeDrawing(panel, Modifier.size(180.dp, 48.dp))
```

The callback runs on the render thread. `context.graphics` is the version's `GuiGraphics` (1.20.1, 1.21.1) or `GuiGraphicsExtractor` (26.x), with coordinates from the component's top-left corner; `width` and `height` are in GUI units, `pixelWidth` and `pixelHeight` in pixels.

Give the component a size, since a native drawing has none of its own. Clip, alpha and transform modifiers work as usual, and the image clips whatever the callback draws past its edges.

The callback may read the game's state but not Compose state. To draw different data, create a new handle; reusing a handle at one size shares its image. Drawings refresh every game tick by default, and only while they are on screen.

Pass `nativeDrawingOptions = NativeDrawingOptions(cacheCapacity, preparationsPerFrame)` to a screen or HUD layer to keep hidden drawings cached (0–128, none by default) or to change how many drawings may run per frame (1–64, 4 by default).

## Large grids

Icons are drawn into atlas pages of up to 64 icons each. A page redraws only its due icons, so an animated item doesn't redraw the icons beside it.

Set `nativeItemOptions` when creating a `ComposeScreen`, `ComposeMenuScreen`, `ComposeInventoryScreen` or `ComposeHudLayer` to tune this for that screen or layer:

```kotlin
class CatalogScreen(title: Component) :
    ComposeScreen<CatalogState, CatalogAction>(title, nativeItemOptions = NativeItemOptions(cacheCapacity = 512)) { … }

class StorageScreen(menu: StorageMenu, inventory: Inventory, title: Component) :
    ComposeInventoryScreen<StorageMenu, Unit, Nothing>(
        menu,
        title,
        nativeItemOptions = NativeItemOptions(cacheCapacity = 1024),
    ) { … }
```

`NativeItemOptions` is in `dev.compixel.forge.item`.

| Option | Default | Range | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | 128; 256 for inventory screens | 1–1024 | Icons kept while fewer are on screen. An icon that scrolls out of view stays cached while it fits, and returns without being drawn again |
| `preparationsPerFrame` | 64 | 1–1024 | Maximum number of icons drawn in one frame |
| `imageSize` | The pixels each icon is laid out with | 16–256 | Pixel size used to draw each 16×16 icon. By default an icon is drawn with the pixels it is laid out with, so it stays sharp at any size and matches Minecraft's own items at 16 dp. A fixed size draws each icon once and scales it on screen |

A screen that shows more icons than `preparationsPerFrame` fills in over a few frames. A page of 64 icons at 16 dp takes about 1 MB of GPU memory at GUI scale 2 and 4 MB at scale 4.

## See also

- [Container screens](inventory.md) show items, counts and tooltips for real menu slots on their own.
- [HUD layers](hud.md) can show item icons too; tooltips need a screen.
