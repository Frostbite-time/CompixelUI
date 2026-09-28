# Compose MC

Compose MC is a library that other mods use to build their screens. It adds nothing to the game on its own: install it when a mod you play requires it.

![A waypoint browser built with Compose MC in Minecraft: a grid of location previews and a details panel](https://raw.githubusercontent.com/Frostbite-time/compose-mc/main/docs/assets/hero-en.png)

## What you get

In mods built with Compose MC:

- **Screens that fit Minecraft**: buttons, sliders, tabs, menus, tooltips and windows in a crisp pixel style.
- **Real items**: icons with their animations and enchantment glint, and the same tooltips as in your inventory.
- **Inventories that work as usual**: clicking, dragging and shift-clicking behave like vanilla, and recipe viewers and other inventory mods keep working.
- **Proper typing**: text fields follow your keyboard layout and support selection, copy and paste, and input methods for languages such as Chinese and Japanese.
- **Friendlier config screens**: mods can use its settings editor, with a tab per file, search, value checks, defaults and undo.
- **Colors you can change**: resource packs can recolor any Compose MC screen, and Light and Twilight themes are built in.
- **Smooth rendering**: screens are drawn on the GPU through the same graphics API as the game, including Vulkan on Minecraft 26.2 and 26.3.

| Controls | Items and tooltips |
| --- | --- |
| ![A menu opened from a button](https://raw.githubusercontent.com/Frostbite-time/compose-mc/main/docs/assets/gallery-ore-en.png) | ![An item grid showing a Minecraft tooltip](https://raw.githubusercontent.com/Frostbite-time/compose-mc/main/docs/assets/gallery-items-en.png) |
| **Inventories** | **HUD overlays** |
| ![A chest laid out with Compose](https://raw.githubusercontent.com/Frostbite-time/compose-mc/main/docs/assets/gallery-storage-en.png) | ![A quest panel over the game view](https://raw.githubusercontent.com/Frostbite-time/compose-mc/main/docs/assets/gallery-hud-en.png) |

## Installation

Download the file for your Minecraft version and put it in your `mods` folder, next to the mod that needs it. Each version comes as two files; install only one of them:

| File | Choose it if |
| --- | --- |
| Ends in `-with-kotlin.jar` | You don't use Kotlin for Forge. This is the right file for most players. |
| The other `.jar` | You already have [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge) installed. |

Don't combine the `-with-kotlin` file with Kotlin for Forge: the two copies of Kotlin conflict.

## Supported versions

| Minecraft | Loader | Graphics | Kotlin for Forge |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.2.18 or newer | OpenGL | 4.12.0 |
| 1.21.1 | NeoForge 21.1.1 or newer | OpenGL | 5.12.0 |
| 26.1.2 | NeoForge 26.1.2.0-beta or newer | OpenGL | 6.3.0 |
| 26.2 | NeoForge 26.2.0.0-beta or newer | OpenGL, Vulkan | 6.3.0 |
| 26.3 | NeoForge 26.3.0.0-beta or newer | OpenGL, Vulkan | Not supported yet; use `-with-kotlin` |

The Kotlin for Forge column shows the versions tested with the plain file.

Each file supports Windows, Linux and macOS on x64 and arm64, though testing so far has covered Windows with NVIDIA graphics. Other setups are expected to work, and reports are welcome. Shaders and heavily modified GUIs haven't been tested widely.

## Change the colors

Resource packs can recolor Compose MC screens without any code. For example, a resource pack with this file at `assets/composemc/composemc/ore_themes/default.json` switches every Compose MC screen to the built-in Twilight theme:

```json
{
  "format": 1,
  "preset": "twilight"
}
```

Enable the pack, or press F3+T to reload if it's already on. Mods that choose their own theme or colors may keep them. The [theme guide](https://github.com/Frostbite-time/compose-mc/blob/main/docs/en/themes.md) covers custom colors, per-mod themes and every color name.

![Compose MC controls in the Twilight theme](https://raw.githubusercontent.com/Frostbite-time/compose-mc/main/docs/assets/ore-twilight.png)

## Troubleshooting

**"Compose MC requires compatible Kotlin…" at startup.** You have the plain file without a suitable Kotlin for Forge. Install Kotlin for Forge for your Minecraft version, or replace the file with the `-with-kotlin` one.

**A mod asks for a different Compose MC version.** Download the version it names.

**A screen looks wrong, or the game crashes when one opens.** Add `-Dcomposemc.backend=cpu` to the JVM arguments in your launcher and try again. Compose MC then draws on the CPU instead of the graphics card: slow, but it shows whether the graphics driver is involved. Please report the problem either way.

## Reporting problems

If only one mod's screens are affected, start with that mod's issue tracker. Otherwise, open an issue on [GitHub](https://github.com/Frostbite-time/compose-mc/issues) with your Minecraft and loader versions, the Compose MC file you installed, and `logs/latest.log` or the crash report.

## For mod developers

Compose MC lets you write your mod's screens, inventories and HUDs in Kotlin with Jetpack Compose:

```kotlin
Minecraft.getInstance().setScreen(ComposeScreen(Component.literal("Counter")) {
    var count by remember { mutableStateOf(0) }
    OreScreen("Counter") {
        OreText("Clicked $count times")
        OreButton("Click me", onClick = { count++ })
    }
})
```

It also covers container screens with real slots, menu synchronization between server and client, and ready-made config screens. Start with [Getting started](https://github.com/Frostbite-time/compose-mc/blob/main/docs/en/getting-started.md), or browse [all guides](https://github.com/Frostbite-time/compose-mc/blob/main/docs/README.md) and the [source code](https://github.com/Frostbite-time/compose-mc).

## License

Compose MC is released under the [MIT License](https://github.com/Frostbite-time/compose-mc/blob/main/LICENSE). Bundled libraries keep their own licenses; see the [third-party notices](https://github.com/Frostbite-time/compose-mc/blob/main/THIRD-PARTY-NOTICES.md). Text uses the [Monocraft](https://github.com/IdreesInc/Monocraft) font by Idrees Hassan, under the [SIL Open Font License 1.1](https://github.com/Frostbite-time/compose-mc/blob/main/ui-ore/src/main/resources/dev/composemc/ui/ore/Monocraft-LICENSE.txt).

Compose MC is not an official Minecraft product and is not associated with Mojang or Microsoft.
