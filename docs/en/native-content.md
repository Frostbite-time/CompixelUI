# Native items and tooltips

[简体中文](../zh-CN/native-content.md) · [Documentation](../README.md)

All supported adapters can display Minecraft item images and native tooltips inside Compose. Examples here use `dev.composemc.neoforge` on 1.21.1; Forge 1.20.1 uses the corresponding `dev.composemc.forge` API.

![Native Minecraft items within an Ore interface](../assets/native-items.png)

*Fresh capture from the packaged Minecraft 1.21.1 / NeoForge OpenGL client. The item images and item tooltip are rendered by Minecraft.*

## Capture first, compose second

Create an `ItemIcon` snapshot on the client render thread, then pass its handle into the composition. Do not read a live `ItemStack` from Compose.

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composemc.neoforge.*
import dev.composemc.ui.ore.layout.OreScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

fun openItemScreen(stack: ItemStack) {
    val minecraft = Minecraft.getInstance()
    val icon = ItemIcon.snapshot(stack)
    minecraft.setScreen(NeoForgeComposeScreen(
        Component.literal("Item details"), parent = minecraft.screen,
    ) {
        OreScreen("Item details") {
            MinecraftItemTooltip(icon) {
                MinecraftItemIcon(icon, Modifier.size(32.dp))
            }
        }
    })
}
```

The snapshot owns a copy of the stack. Create another snapshot when its content or count changes. Reuse a handle across frames rather than recreating it on every tick. The same handle can appear in multiple places with different transforms or sizes.

## Animation and preparation

| Refresh policy | Meaning |
| --- | --- |
| `IconRefresh.AUTO` | Default item policy; detect native model/texture animation |
| `IconRefresh.STATIC` | Intentionally freeze the prepared image until invalidation |
| `IconRefresh.GAME_TICK` | Refresh on game ticks |
| `IconRefresh.FRAME` | Refresh each frame |
| `IconRefresh.every(milliseconds)` | Explicit 16–60,000 ms interval |

`ItemIcon.drawn(description, drawing, refresh)` supplies custom native drawing inside a 16×16 GUI area. Its callback runs on the render thread and defaults to `GAME_TICK`. Capture the data needed to draw a resource; business resource types remain in the consumer. A drawn icon does not contain an item-tooltip snapshot.

`NativeItemOptions` defaults to a 64-pixel image resolution, 128 cached images and 8 preparations per frame. Inventory screens use 256 images and 16 preparations per frame. Size the cache for visible variants and lazy-layout prefetch. Active demand pins entries; preparation remains bounded and can take multiple frames for a new grid.

The native renderer prepares images on the game thread. Compose consumes immutable image handles with clipping, transforms and opacity. The 1.20.1/1.21.1 GL paths transfer prepared images on the GPU. Minecraft 26.x captures native GUI commands into a small offscreen target, reads pixels back asynchronously and caches the resulting Compose image. This adds preparation cost for newly visible or animated icons; the per-frame preparation budget limits that cost. The adapter owns temporary targets and retains resources as long as recorded frames need them. Reload invalidates prepared images; final screen removal releases owned resources.

## Native tooltip behavior

`MinecraftItemTooltip(icon, delayMillis = 500) { … }` defaults to a 500 ms hover delay. `enabled=false` suppresses it. Pointer presses, scrolling and key presses restart the delay; blur hides it. The popup flips and clamps to the viewport. On 1.21.1, rich content is wrapped or scaled to fit, and visible content refreshes at most once per 100 ms unless invalidated.

This wrapper preserves the trigger's composition, click handling and remembered state. On 1.21.1 the renderer uses Minecraft item tooltip components and NeoForge gathering, font, pre-render and color hooks. Drawing-hook coordinates are local to the prepared target; Compose owns placement, so native screen-position overrides do not determine the popup position. Other targets use their version-specific tooltip pipeline.

Use [OreTooltip](ore-ui.md) for arbitrary nested, interactive Compose bodies. It appears immediately and supports dwell locking; a native item tooltip is rendered content and does not expose its internal words as arbitrary interactive composables. You can place an item image inside an Ore tooltip and wrap that image in another Ore tooltip.

## Diagnostics and integration

Read `nativeItemStatistics`, `nativeTooltipStatistics` and `rendererStatistics` on the client thread. The [build guide](build-and-test.md) describes reproducible captures and opt-in profiling.

For real menu slots, use [ComposeMenuSlots](inventory.md); it already supplies item visuals, carried-stack behavior and native slot tooltips. Attaching a second tooltip implementation to the same slot can create duplicate hover content. The image API alone does not implement inventory actions.
