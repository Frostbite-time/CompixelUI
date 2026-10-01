# Items and tooltips

[简体中文](../zh-CN/items.md) · [All guides](../README.md)

CompixelUI draws real Minecraft items inside Compose, with the same models, animations and enchantment glint as an inventory, plus the game's own item tooltips.

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

The callback runs on the game/render thread. `context.graphics` is the version's native `GuiGraphics` (1.20.1/1.21.1) or `GuiGraphicsExtractor` (26.x). Coordinates start at the component's top-left corner. `width` and `height` are local GUI units, rounded up to cover the image; `pixelWidth` and `pixelHeight` are its exact physical dimensions. One native GUI unit occupies `guiScale` pixels. A screen's custom UI density can therefore make one Compose dp differ from one native GUI unit.

Give the component a size or bounded fill modifier; native drawings have no intrinsic size. Use ordinary Compose clip, alpha and transform modifiers. The drawing is captured before those display transforms; input still belongs to Compose. Native scissor rectangles use the local target. The final image clips anything past its edges. Dimensions are not limited to 256 pixels, but must fit the graphics device's texture limits; memory use grows with width × height.

Do not read Compose state inside the callback or retain its graphics object. Pass immutable snapshots through a reused handle, replacing the handle when its snapshot changes. A callback may read game-thread-owned state for animated content. The default refresh is `NativeRefresh.GAME_TICK`; `AUTO` also means one refresh per game tick for custom drawings. Refreshes happen only while displayed and within the preparation budget.

`nativeDrawingOptions = NativeDrawingOptions(cacheCapacity = 0, preparationsPerFrame = 4)` is available on all four screen/HUD hosts. Cache capacity (0–128) controls images retained when fewer are visible; the default releases hidden targets. Preparations per frame (1–64) bounds native draws across independent rectangular targets. Reusing one handle at one size shares its image; different sizes get separate targets. During continuous resizing, the nearest existing image remains visible until the new size settles. Resource reloads and GUI-scale changes invalidate static drawings too.

Item icons retain their compact atlas allocation and native item handling. Rectangles use independent targets so native clipping and viewport-dependent drawing work correctly. Both use the same underlying image scheduler, Compose publication and renderer-owned retirement.

## Large grids

Every icon on screen gets its image, however many there are. Icons are drawn into atlas pages of up to 64 icons each. A page redraws only its due icons, so an animated item doesn't redraw the icons beside it. Pages are added as more icons appear, and discarded once their icons are gone.

Set `nativeItemOptions` when creating a `ComposeScreen`, `ComposeMenuScreen`, `ComposeInventoryScreen` or `ComposeHudLayer` to tune this for that screen or layer:

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

`NativeItemOptions` is in `dev.compixel.forge.item`.

| Option | Default | Range | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | 128; 256 for inventory screens | 1–1024 | Icons kept while fewer are on screen. An icon that scrolls out of view stays cached while it fits, and returns without being drawn again |
| `preparationsPerFrame` | 64 | 1–64 | Maximum number of icons drawn in one frame, and the number of icons on one atlas page |
| `imageSize` | The pixels each icon is laid out with | 16–256 | Pixel size used to draw each 16×16 icon. By default an icon is drawn with the pixels it is laid out with, so it shows pixel for pixel at any size and matches Minecraft's own item rendering at 16 dp. An icon shown at two sizes is drawn at both; a size that keeps changing, as in an animation, shows the nearest drawn size until it settles. A fixed size draws each icon once and resamples it on screen |

A screen that shows more icons than `preparationsPerFrame` fills in over a few frames. Each page holds icons of one size: 64 icons shown at 16 dp take about 4 MB of GPU memory at GUI scale 4, about 1 MB at scale 2 and about 9 MB at scale 6, and larger icons take more with the square of their size. Reusing the same `ItemIcon` handle shares one image; separately created handles get separate images, even for identical stacks.

## See also

- [Container screens](inventory.md) show items, counts and tooltips for real menu slots on their own.
- [HUD layers](hud.md) can show item icons too; tooltips need a screen.
