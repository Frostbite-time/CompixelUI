# Images and fonts

[简体中文](../zh-CN/resources.md) · [All guides](../README.md)

Use Minecraft's resource paths directly in Compose. CompixelUI reads images and fonts from the active resource packs and updates them when resources reload.

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

## Replace Ore's font

Ore automatically reads `compixel:font/compixel.ttf`. A resource pack replaces the bundled font by providing:

```text
assets/compixel/font/compixel.ttf
```

The bundled font stays XZ-compressed in the mod archive. At startup CompixelUI unpacks it into a required, hidden built-in resource pack at the bottom of Minecraft's pack stack. Minecraft's resource manager exposes the ordinary TTF path and handles pack priority and reloads for it.

Supply a valid TTF or OTF file at that path. F3+T and pack changes update Ore text, text fields, menus and dialogs in place, preserving live UI state. Removing the pack restores the bundled font. An invalid replacement logs a warning and uses the bundled font until the next reload.

This is a separate UI font resource; Minecraft's `font/*.json` providers and bitmap font sheets are not converted. A pack that only changes Minecraft's own font must also supply the file above to change Ore.

`OreTypography` styles with a null `fontFamily` inherit this resource font. Custom sizes and line heights can be kept in a remembered or long-lived `OreTypography`; an explicitly supplied font family stays under the consumer's control. Desktop hosts continue to use the bundled font.

## Use another font

Put your font at `assets/examplemod/font/body.ttf` and use its complete resource path:

```kotlin
import dev.compixel.forge.resources.fontResource

val font = fontResource("examplemod:font/body.ttf")
OreText("Hello", style = OreTheme.typography.body.copy(fontFamily = font))
```

`fontResource` returns an ordinary Compose `FontFamily`, usable in `TextStyle`, text fields, or another design system's typography, including Material3. It accepts the same native identifier overloads as `painterResource`. Missing or invalid files use `FontFamily.Default`, or the `fallback` family supplied by the caller. Hosts without a resource source also use that fallback.

Compositions share decoded font families, and changed font bytes receive a new Compose font identity on reload, so existing text is measured and drawn with the replacement. Consumers do not close the returned family.

## Shared content and custom hosts

Minecraft-free shared content imports `dev.compixel.ui.resources.painterResource` or `dev.compixel.ui.resources.fontResource` and uses full namespaced string paths. Minecraft hosts supply `LocalResources` automatically.

A custom host supplies `LocalResources` with a `UiResources` backed by its own `ResourceSource`. The source returns encoded file bytes, or null for a missing path. Call `UiResources.reload()` on the host's UI thread after its resources change. These UI contracts live in `ui-core`; the host only supplies file access and reload notifications.

Other resource types can implement `ResourceType<T>` and call `UiResources.load(path, type)`. Reuse a stable decoder instance. Each path/type pair shares one decoded result while compositions use it; failed results are also cached and retried on reload. Decoders and their returned values own their storage: cache eviction does not close values still held by consumers or recorded frames.
