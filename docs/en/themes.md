# Color schemes

[简体中文](../zh-CN/themes.md) · [All guides](../README.md)

CompixelUI screens take their colors from color schemes. Players switch schemes and change any color in game, and resource packs and modpacks add or change schemes with small JSON files; no code is needed. Schemes change colors only: layouts, item textures and Minecraft's own item tooltips stay as they are.

Each mod owns its colors. All screens of one mod share one list of schemes, so choosing a scheme recolors every screen of that mod and none of another mod's. Screens in plain Ore style belong to CompixelUI itself, under the namespace `compixel`; a mod with a design of its own has its own list. See [For mod developers](#for-mod-developers).

## Built-in schemes

Ore comes with three schemes:

| Scheme | Look |
| --- | --- |
| Default | The original Ore style: dark gray panels, light text, green accents |
| Light | Light gray metal panels, dark text, violet accents |
| Twilight | Deep navy panels, pale text, teal accents |

![Ore controls in the Light scheme](../assets/ore-light.png)

![Ore controls in the Twilight scheme](../assets/ore-twilight.png)

A mod that styles its screens with Ore under its own name offers these three as well, next to its own schemes.

## Change colors in game

Screens can open the color editor, for example from a palette button in a mod's title bar; each mod decides where. CompixelUI's own schemes, which screens in plain Ore style use, open from its **Config** button in the mod list. The editor shows a preview of the mod's screens on the left and its colors on the right:

1. Choose a scheme at the top. Open screens of the mod switch at once.
2. Choose a color in the list and change it with the picker: saturation and brightness, hue, and opacity.
3. Colors you changed are marked in the list. **Restore** gives the selected color back its scheme's color, and **Restore all** every color you changed in the scheme.

Some colors follow others, such as a button's hover and pressed shades, a step darker than the button. They are listed under **Shades that follow other colors** and change with the colors they follow. Once you change one yourself, it keeps your color until you restore it.

Your choices are saved for every mod and scheme in `config/compixel-colors.json`. They apply on top of every resource pack.

### Export for a resource pack or modpack

**Export** writes your colors as a resource pack folder in `resourcepacks`, named after the name you enter:

| Option | The pack |
| --- | --- |
| Replace *scheme* | Changes that scheme to your colors for everyone who enables it. |
| Save as a new scheme | Adds a scheme with the name you entered, starting from the current scheme, with your colors. |
| Make it the default | Also makes the exported scheme the one used until a player chooses another. |

Enable the pack to use it, share it with other players, or include it in a modpack. A default applies only to players who haven't chosen a scheme for that mod themselves.

## Write a scheme file

A scheme is a JSON file in a resource pack at `assets/<namespace>/compixel/schemes/<name>.json`. The namespace is the mod's ID, `compixel` for CompixelUI's own screens; `assets`, `compixel` and `schemes` stay as they are. Names are lower case, and a `/` in a name becomes a subfolder.

```text
your-resource-pack/
├── pack.mcmeta
└── assets/
    └── compixel/
        └── compixel/
            └── schemes/
                └── ocean.json
```

This file adds an Ocean scheme to CompixelUI's screens. It starts from the default scheme and changes three colors:

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
| `format` | Yes | `1` | The version of this file format. It isn't the Minecraft version or the pack's format. |
| `name` | No | A name, or a translation key from a language file | What the editor calls the scheme. Without it, the editor shows the file name. |
| `extends` | No | Another scheme of the same mod, such as `"default"` | Starts from that scheme's colors. |
| `colors` | No | Colors by name | Sets these colors. |

Colors are written `"#RRGGBB"`, or `"#RRGGBBAA"` with an opacity from `00` (transparent) to `FF` (opaque). For example, `"#00000080"` is half-transparent black. Write only the colors you want to change; the others keep the colors of the scheme the file extends, or the mod's own colors.

To change an existing scheme instead, put a file of the same name in your pack, such as `assets/compixel/compixel/schemes/light.json` for the built-in Light scheme. It needs only `format` and the colors you change.

### Colors that follow others

Some colors follow others through a rule, such as a hover shade a step darker than its button. When a file changes `primary`, the hover, pressed and edge shades of primary buttons and the text on them follow it. A file can also set such a color itself: it then keeps that color until a higher pack or the player changes a color it follows, and follows again from there. [Ore's colors](#ore-color-names) lists what each one follows.

## Choose the default and the order

An optional `assets/<namespace>/compixel/schemes.json` chooses the scheme a mod uses until the player picks one, and the order of the editor's list:

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

Each file changes only the colors it contains. For example, if a lower-priority `ocean.json` sets `panel` and `text`, and a higher-priority one sets only `panel`, the panel changes and the text keeps the lower pack's color. `name`, `extends` and each field of `schemes.json` come from the highest-priority pack that sets them.

## Ore color names

These are the names Ore's colors take in `colors`, with their typical uses; several controls share some of them.

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

Put the pack in the instance's `resourcepacks` folder and enable it. After editing a file, press **F3+T** to reload: open screens update in place and keep their typed text, scroll position and menu state.

If a change doesn't show up, check:

1. The file is in the mod's namespace, `assets/<namespace>/compixel/schemes/`; mods name their namespace in their documentation.
2. The scheme is the one in use: choose it in the color editor, or make it the default in `schemes.json`. A scheme the player chose comes before the default.
3. The pack is enabled, and no higher-priority pack sets the same color.
4. The player hasn't changed the same color in the color editor; **Restore** removes such a change.
5. The JSON is valid, has `"format": 1`, and spells every color name and value correctly.
6. `logs/latest.log` mentions `Skipping` or `Ignoring`, followed by the file, the pack and the reason.

A file with broken JSON or another `format` is skipped as a whole. An unknown field or color name, or an invalid value, is ignored on its own, and the rest of the file still applies. Scheme files are read only on the client.

## For mod developers

### Give your screens schemes of their own

Screens use Ore with CompixelUI's schemes by default. Name your mod to give its screens a list of their own, which players and packs change without affecting other mods:

```kotlin
class StorageScreen :
    ComposeScreen<StorageState, StorageAction>(Component.literal("Storage"), design = OreDesign("examplemod")) {
    // snapshot, handle and Content as usual
}
```

`ComposeMenuScreen`, `ComposeInventoryScreen` and `ComposeHudLayer` take `design` too. To ship schemes, put their files under `src/main/resources/assets/examplemod/compixel/schemes/`, and a `schemes.json` there to choose their default and order. Resource packs can still change them.

### Open the color editor

Screens provide `LocalColorEditor`. Calling it opens the color editor for the screen's design over the screen, which comes back when the player closes the editor. It is null where no editor can open, such as in HUD layers.

```kotlin
LocalColorEditor.current?.let { openColorEditor -> OreButton("Colors", openColorEditor) }
```

Other code can open `ColorEditorScreen(parent, design)` directly.

### Declare your own colors

When your mod draws parts of its own, declare their colors in a schema of its own instead of borrowing Ore's, so players and packs can change them:

```kotlin
object StorageColors : ColorSchema("examplemod") {
    val window = color("window", "window", Color(0xE0101418))
    val text = color("text", "text", Color(0xFFF2F5F9))
    val accent = color("accent", "accent", Color(0xFF22C7F0))
    val accentHover = derived("accentHover", "accent", accent) { lerp(it[accent], Color.Black, .16f) }
}
```

- `color(key, group, default)` declares a color: its name in scheme files, the group the editor lists it under, and its default. The editor lists colors in the order you declare them.
- `derived(key, group, follows…) { rule }` declares a color that follows others; the rule may read only the colors it follows. An optional `default` gives it a color of its own until something changes a color it follows. The editor lists these under **Shades that follow other colors**.
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

- Read your colors with `LocalStorageColors.current[StorageColors.accent]`. Open screens follow every change of scheme or color and keep their state.
- `owner` names the namespace of the design's scheme files and of the player's choices. Its scheme `default` uses the schema's defaults when no file defines it; `SchemeOwner("examplemod", StorageColors, default = "dark")` starts from your `dark.json` instead.
- `preview()` gives the editor a small sample of your screens to show beside the colors. The editor calls it on the game thread as it opens, so it can snapshot items with `ItemIcon.snapshot` and read translations there before returning the content.
- `OreColors.values(...)` sets Ore's colors exactly; Ore colors you don't set keep Ore's defaults or follow the colors you set.

`OreTheme(OreColors.scheme("light")) { … }` applies fixed colors that neither packs nor players change. Hosts outside Minecraft provide scheme files and choices as `Schemes` through `LocalSchemes`.

Your own controls give the native click sound like Ore's buttons by calling `LocalUiFeedback.current.activate()` when the user activates them, for example right after `onClick`; the screen plays it on the game thread.
