# Color schemes

[简体中文](../zh-CN/themes.md) · [All guides](../README.md)

CompixelUI screens take their colors from color schemes. Players switch schemes and change any color in game, and resource packs and modpacks add or change schemes with JSON files.

Each mod owns its colors: all screens of one mod share one list of schemes. Screens in plain Ore style use CompixelUI's own list, under the namespace `compixel`. See [For mod developers](#for-mod-developers).

## Built-in schemes

Ore comes with three schemes:

| Scheme | Look |
| --- | --- |
| Default | The original Ore style: dark gray panels, light text, green accents |
| Light | Light gray metal panels, dark text, violet accents |
| Twilight | Deep navy panels, pale text, teal accents |

![Ore controls in the Light scheme](../assets/ore-light.png)

![Ore controls in the Twilight scheme](../assets/ore-twilight.png)

A mod that styles its screens with Ore under its own name offers these three next to its own schemes.

## Change colors in game

Each mod decides where its screens open the color editor, for example from a palette button in a title bar. CompixelUI's own schemes open from its **Config** button in the mod list. The editor shows a preview of the mod's screens beside its colors:

1. Choose a scheme at the top.
2. Choose a color in the list and change it with the picker.
3. **Restore** gives the selected color back its scheme's color, and **Restore all** every color you changed in the scheme.

Colors that follow others, such as a button's hover shade, are listed under **Shades that follow other colors** and change with the colors they follow. Once you change one, it keeps your color until you restore it.

Your choices are saved in `config/compixel-colors.json` and apply on top of every resource pack.

### Export for a resource pack or modpack

**Export** writes your colors as a resource pack folder in `resourcepacks`, named after the name you enter:

| Option | The pack |
| --- | --- |
| Replace *scheme* | Changes that scheme to your colors. |
| Save as a new scheme | Adds a scheme with this name, starting from the current scheme, with your colors. |
| Make it the default | Also makes the exported scheme the one used until a player chooses another. |

Enable the pack to use it, or share it and include it in a modpack.

## Write a scheme file

A scheme is a JSON file in a resource pack at `assets/<namespace>/compixel/schemes/<name>.json`, where the namespace is the mod's ID. Names are lower case, and a `/` in a name becomes a subfolder.

```text
your-resource-pack/
├── pack.mcmeta
└── assets/
    └── compixel/
        └── compixel/
            └── schemes/
                └── ocean.json
```

This file adds an Ocean scheme to CompixelUI's screens, starting from the default scheme:

```json
{
  "format": 1,
  "name": "Ocean",
  "extends": "default",
  "colors": {
    "panel": "#1F2A38",
    "raised": "#2A3A4E",
    "primary": "#2F7FB8"
  }
}
```

| Field | Required | Value | What it does |
| --- | --- | --- | --- |
| `format` | Yes | `1` | The version of this file format. |
| `name` | No | A name, or a translation key from a language file | What the editor calls the scheme; without it, the file name. |
| `extends` | No | Another scheme of the same mod, such as `"default"` | Starts from that scheme's colors. |
| `colors` | No | Colors by name | Sets these colors. |

Colors are written `"#RRGGBB"`, or `"#RRGGBBAA"` with an opacity from `00` (transparent) to `FF` (opaque). Write only the colors you change.

To change an existing scheme, put a file of the same name in your pack, such as `assets/compixel/compixel/schemes/light.json` for the built-in Light scheme.

### Colors that follow others

Some colors follow others through a rule: when a file changes `primary`, the hover, pressed and edge shades of primary buttons and the text on them follow. A file can also set such a color; it follows again once a higher pack or the player changes a color it follows. [Ore color names](#ore-color-names) lists what each one follows.

## Choose the default and the order

`assets/<namespace>/compixel/schemes.json` chooses the scheme a mod uses until the player picks one, and the order of the editor's list:

```json
{
  "format": 1,
  "default": "ocean",
  "order": ["ocean", "default", "light", "twilight"]
}
```

| Field | Required | Value | What it does |
| --- | --- | --- | --- |
| `format` | Yes | `1` | The version of this file format. |
| `default` | No | A scheme of the mod | The scheme used until the player chooses one. |
| `order` | No | A list of schemes | Lists these first in the editor; the mod's default, its built-in schemes and the other files follow. |

## How packs stack

The colors of a scheme are built up in this order:

1. The mod's own colors.
2. The scheme it `extends`, if any, built up the same way.
3. The scheme's own colors, for a built-in scheme.
4. Each pack's file of the scheme, from the mod's bundled copy to the highest-priority pack.
5. The player's changes from the color editor.

Each file changes only the colors it contains. `name`, `extends` and each field of `schemes.json` come from the highest-priority pack that sets them.

## Ore color names

| Name | Where it appears |
| --- | --- |
| `backdrop` | The dimming layer over the game behind a screen |
| `scrim` | The dimming layer behind dialogs |
| `panel` | Main panels, text fields and similar backgrounds |
| `raised` | Raised surfaces, title bars and some tooltips |
| `hovered` | Hovered list rows, icon buttons and similar |
| `edge` | Dark control edges and dividers |
| `highlight` | Light edges of panels, slots and similar |
| `frameEdge` | The outer frame of windows |
| `ledge` | The strip under a window or panel that gives it depth |
| `bevelLight` | The color mixed into generated highlights, normally white |
| `text` | Body text and typed text |
| `mutedText` | Secondary text and placeholders |
| `disabledText` | Text on disabled controls |
| `disabledEdge` | Edges of disabled controls |
| `ink` | A dark text color for mods to use |
| `focus` | The outline around the focused control |
| `slot` | Item slot background |
| `slotEdge` | Item slot dark edge |
| `primary` | Primary buttons and selected states |
| `secondary` | Secondary buttons and similar |
| `buttonBorder` | Button frames, and scrollbar thumb frames |
| `danger` | Destructive buttons, and input errors |
| `switchTrack` | Unchecked switch tracks, and unselected radio buttons |
| `trackEmpty` | The empty part of a slider |
| `trackEmptyLight` | The light edge of the empty part, and scrollbar rails |

These follow other colors until a file or the player sets them:

| Name | Where it appears | Follows |
| --- | --- | --- |
| `selection` | Selected text in text fields | `primary` |
| `markedSlot` | Marked item slot background | `slot`, `primary` |
| `slotHover` | Color blended into a hovered slot | `slot`, `primary` |
| `slotHoverEdge` | Outline of hovered and marked slots | `primary`, `bevelLight` |
| `primaryHover` | Hovered primary buttons | `primary` |
| `primaryPressed` | Pressed primary buttons and similar | `primary` |
| `primaryEdge` | Dark and bottom edge of primary controls | `primary` |
| `onPrimary` | Text, icons and checkmarks on primary colors | `primary` |
| `trackFilled` | The filled part of a slider | `primary` |
| `trackFilledLight` | The light edge of the filled part | `primary`, `bevelLight` |
| `secondaryHover` | Hover color of some secondary controls; secondary buttons use `secondaryButtonActive` | `secondary` |
| `secondaryPressed` | Pressed color of some secondary controls; secondary buttons use `secondaryButtonActive` | `secondary` |
| `secondaryEdge` | Dark and bottom edge of secondary controls | `secondary` |
| `onSecondary` | Text and icons on secondary colors | `secondary` |
| `secondaryButtonActive` | Secondary button face while hovered or pressed | `secondary` |
| `secondaryButtonLightEdge` | Top and left bevel of secondary buttons | `secondary`, `bevelLight` |
| `secondaryButtonDarkEdge` | Bottom and right bevel of secondary buttons | `secondary`, `bevelLight` |
| `secondaryButtonCorner` | Corner pixels between the two bevels | `secondary`, `bevelLight` |
| `dangerHover` | Hovered destructive buttons | `danger` |
| `dangerPressed` | Pressed destructive buttons | `danger` |
| `dangerEdge` | Dark and bottom edge of destructive buttons | `danger` |
| `onDanger` | Text and icons on destructive buttons | `danger` |

## Try it in game

Put the pack in the instance's `resourcepacks` folder and enable it. After editing a file, press **F3+T** to reload: open screens update in place and keep their state.

If a change doesn't show up, check that:

1. The file is in the mod's namespace, `assets/<namespace>/compixel/schemes/`.
2. The scheme is the one in use: chosen in the color editor, or the default in `schemes.json`.
3. No higher-priority pack and no change in the color editor sets the same color.
4. `logs/latest.log` has no `Skipping` or `Ignoring` line for the file; such a line gives the reason.

A file with broken JSON or another `format` is skipped; an unknown field or color, or an invalid value, is ignored on its own.

## For mod developers

### Give your screens schemes of their own

Screens use Ore with CompixelUI's schemes by default. Name your mod to give its screens a list of their own:

```kotlin
class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("Storage"), design = OreDesign("examplemod")) {
    // snapshot, handle and Content as usual
}
```

`ComposeMenuScreen`, `ComposeInventoryScreen` and `ComposeHudLayer` take `design` too. To ship schemes, put their files under `src/main/resources/assets/examplemod/compixel/schemes/`, with a `schemes.json` for their default and order.

### Open the color editor

Screens provide `LocalColorEditor`, which opens the color editor for the screen's design over the screen. It is null in HUD layers.

```kotlin
LocalColorEditor.current?.let { openColorEditor -> OreButton("Colors", openColorEditor) }
```

Other code can open `ColorEditorScreen(parent, design)` directly.

### Declare your own colors

When your mod draws parts of its own, declare their colors in a schema of its own:

```kotlin
object StorageColors : ColorSchema("examplemod") {
    val window = color("window", "window", Color(0xE0101418))
    val text = color("text", "text", Color(0xFFF2F5F9))
    val accent = color("accent", "accent", Color(0xFF22C7F0))
    val accentHover = derived("accentHover", "accent", accent) { lerp(it[accent], Color.Black, .16f) }
}
```

- `color(key, group, default)` declares a color: its name in scheme files, the group the editor lists it under, and its default. The editor lists colors in declaration order.
- `derived(key, group, follows…) { rule }` declares a color that follows others; the rule reads only the colors it follows. An optional `default` gives it a color of its own until a color it follows changes.
- The editor takes the names of colors and groups from your language files: `color.examplemod.accent` and `color.examplemod.group.accent`, after the schema's name.

Then give your screens a design that provides these colors:

```kotlin
val LocalStorageColors = staticCompositionLocalOf { StorageColors.defaults }

object StorageDesign : UiDesign {
    override val owner = SchemeOwner("examplemod", StorageColors)

    @Composable
    override fun Decorate(content: @Composable () -> Unit) {
        val colors = owner.colors()
        // The Ore controls you use take their colors from yours.
        val ore =
            remember(colors) {
                OreColors.values(
                    OreColors.panel to colors[StorageColors.window],
                    OreColors.text to colors[StorageColors.text],
                    OreColors.primary to colors[StorageColors.accent],
                )
            }
        OreTheme(ore) { CompositionLocalProvider(LocalStorageColors provides colors, content = content) }
    }

    override fun preview(): @Composable () -> Unit = { StoragePreview() }
}

class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("Storage"), design = StorageDesign)
```

- Read your colors with `LocalStorageColors.current[StorageColors.accent]`.
- `owner` names the namespace of the design's scheme files and of the player's choices. Its default scheme is `default`, which has the schema's defaults when no file defines it; `SchemeOwner("examplemod", StorageColors, default = "dark")` starts from your `dark.json` instead.
- `preview()` returns a sample of your screens for the editor. The editor calls it on the game thread, so snapshot items there with `ItemIcon.snapshot`. Keep it about the size of one window: at Minecraft's automatic GUI scale a 1080p screen leaves it about 230 units of height, and a taller preview scrolls.
- `OreColors.values(...)` sets Ore's colors exactly; the others keep Ore's defaults or follow the ones you set.

`OreTheme(OreColors.scheme("light")) { … }` applies fixed colors. Hosts outside Minecraft provide scheme files and choices as `Schemes` through `LocalSchemes`.

Your own controls give the native click sound like Ore's buttons by calling `LocalUiFeedback.current.activate()` when the user activates them, for example right after `onClick`; the screen plays it on the game thread.
