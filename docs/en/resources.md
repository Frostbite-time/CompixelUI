# Resource images

[简体中文](../zh-CN/resources.md) · [All guides](../README.md)

Use Minecraft's resource paths directly in Compose. CompixelUI reads the image from the active resource packs and updates it when resources reload.

## Show an image

Put an image at `src/main/resources/assets/examplemod/textures/gui/logo.png`, then use its resource path:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.compixel.forge.resources.painterResource

@Composable
fun Logo() {
    Image(
        painter = painterResource("examplemod:textures/gui/logo.png"),
        contentDescription = "Logo",
        modifier = Modifier.size(32.dp),
    )
}
```

Call it from a `ComposeScreen`, menu screen or `ComposeHudLayer`. The host supplies the resource system automatically, including inside popups and dialogs. No loader, `remember` block or reload listener is needed in the consumer.

The result is a standard Compose `Painter`: `Image`'s size, `contentScale`, alpha and `colorFilter`, and `Modifier.paint`, work as usual. Its intrinsic size is the image's pixel size. PNG, JPEG, WebP and other raster formats supported by Skia use the same API.

## Paths

- `examplemod:textures/gui/logo.png` means `assets/examplemod/textures/gui/logo.png`.
- Include the file extension and omit `assets/`.
- An omitted namespace means `minecraft`, so `"textures/block/stone.png"` reads the vanilla stone texture.
- Images from Minecraft, your mod, other mods and resource packs follow the same lookup rules.

The function also accepts the game's native identifier:

| Minecraft | Identifier |
| --- | --- |
| 1.20.1 | `ResourceLocation("examplemod", "textures/gui/logo.png")` |
| 1.21.1 | `ResourceLocation.fromNamespaceAndPath("examplemod", "textures/gui/logo.png")` |
| 26.x | `Identifier.fromNamespaceAndPath("examplemod", "textures/gui/logo.png")` |

All versions use `dev.compixel.forge.resources.painterResource`.

## Scaling

Images use nearest-neighbour sampling by default, keeping Minecraft pixel art crisp. For a smoothly scaled illustration:

```kotlin
val painter = painterResource(
    "examplemod:textures/gui/illustration.png",
    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
)
```

## Resource packs and reloads

Minecraft's highest-priority resource wins. A resource pack replaces an image by providing the same `assets/<namespace>/<path>` file. Reloading with F3+T or changing packs updates the image automatically, including changes to its dimensions. The Compose session stays open, so remembered text, scroll positions and menu state survive.

Missing or undecodable images show a magenta-and-black checkerboard and produce a log warning. If a later reload supplies a valid file, its painter recovers automatically.

Compositions using the same path share one decoded image. It leaves the cache when its last user leaves the composition; existing recorded frames retain their pixels. Consumers do not close the painter or its image.

## Shared content and custom hosts

Minecraft-free shared content can import `dev.compixel.host.resources.painterResource` and use full namespaced string paths. Minecraft hosts supply its `LocalResources` automatically.

A custom host supplies `LocalResources` with a `UiResources` backed by its own `ResourceSource`. The source returns encoded file bytes, or null for a missing path. Call `UiResources.reload()` on the host's UI thread after its resources change.
