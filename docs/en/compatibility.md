# Compatibility

[简体中文](../zh-CN/compatibility.md) · [All guides](../README.md)

This page describes Compose MC **0.1.0-alpha.35**.

## Minecraft versions

| Minecraft | Loader | Java | Graphics |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.23 | 17 | OpenGL |
| 1.21.1 | NeoForge 21.1.250 | 21 | OpenGL |
| 26.1.2 | NeoForge 26.1.2.109 | 25 | OpenGL |
| 26.2 | NeoForge 26.2.0.88 | 25 | OpenGL, Vulkan |
| 26.3 | NeoForge 26.3.0.6-beta | 25 | OpenGL, Vulkan |

Each Minecraft version has its own build of Compose MC, so use the one that matches. The API has the same package and class names on every version, `dev.composemc.forge` included; only the Minecraft and loader types around it differ.

Compose MC is built with Kotlin 2.4.10 and Compose 1.12.0.

## Maven coordinates

| Coordinate | Contents |
| --- | --- |
| `dev.composemc:composemc-<loader>-<minecraft>` | The library; Gradle adds the Compose runtime it needs |
| `dev.composemc:composemc-<loader>-<minecraft>-with-kotlin` | The same, plus the Kotlin libraries |

The loader is `forge` for 1.20.1 and `neoforge` for the others. The 1.20.1 artifact uses SRG names; add it through your toolchain's remapping configuration, as with other Forge mods. Each version also publishes `sources`, `javadoc` and `development` (the F8 preview) classifiers.

## Kotlin

Every release has two player files per Minecraft version:

| File | Kotlin |
| --- | --- |
| `…-with-kotlin.jar` | Included. Don't combine with Kotlin for Forge. |
| `….jar` | Needs a Kotlin provider, such as Kotlin for Forge |

Tested Kotlin for Forge versions: 4.12.0 on 1.20.1, 5.12.0 on 1.21.1, and 6.3.0 on 26.1.2 and 26.2. Kotlin for Forge 6.3.0 doesn't support 26.3, so use the `-with-kotlin` file there. Any provider needs Kotlin 2.2.21 or newer with matching Coroutines and Serialization libraries. The game reports a clear error at startup if they're missing.

## Graphics

Compose MC draws with the same graphics API as the game: OpenGL, or Vulkan when Minecraft 26.2 or 26.3 runs on Vulkan. Set `-Dcomposemc.backend` to `opengl`, `vulkan` or `cpu` to override it; `cpu` is a slow reference renderer for troubleshooting.

Each file contains the native libraries for Windows, Linux and macOS on x64 and arm64. Testing on real hardware has so far covered Windows x64 with NVIDIA graphics. Other systems, graphics drivers and shader mods are expected to work but have not been verified.

## Loader differences

| | Forge 1.20.1 | NeoForge |
| --- | --- | --- |
| Metadata | `mods.toml`, `mandatory = true` | `neoforge.mods.toml`, `type = "required"` |
| Menu screens | `MenuScreens.register` in `enqueueWork` | `RegisterMenuScreensEvent` |
| HUD layers | `RegisterGuiOverlaysEvent` | `RegisterGuiLayersEvent` |
| Config screens | `ConfigScreenHandler.ConfigScreenFactory` | `IConfigScreenFactory` |
| Minecraft values in menu sync | `MinecraftSyncCodecs.buffer` | `MinecraftSyncCodecs.registry` |

## Keyboard and text input

- Letter and punctuation keys follow the player's keyboard layout. Text fields support selection and the clipboard.
- On 26.x, a focused text field opens Minecraft's text input like a vanilla edit box, and input method text is composed inside the field.
- On 1.20.1 and 1.21.1, the operating system's input method window shows text being composed.

## Other mods

Container screens keep the native container screen and its events, so recipe viewers and other container add-ons keep working. Widgets that other mods add to a screen draw above the Compose content and receive input first. Test the combinations your modpack relies on; shaders and heavily modified GUIs have not been tested widely.
