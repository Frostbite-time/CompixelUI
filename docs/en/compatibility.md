# Compatibility

[简体中文](../zh-CN/compatibility.md) · [All guides](../README.md)

## Minecraft versions

| Minecraft | Loader | Java | Graphics |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.2.18 or newer | 17 | OpenGL |
| 1.21.1 | NeoForge 21.1.1 or newer | 21 | OpenGL |
| 26.1.2 | NeoForge 26.1.2.0-beta or newer | 25 | OpenGL |
| 26.2 | NeoForge 26.2.0.0-beta or newer | 25 | OpenGL, Vulkan |
| 26.3 | NeoForge 26.3.0.0-beta or newer | 25 | OpenGL, Vulkan |

Each Minecraft version has its own build of CompixelUI, so use the one that matches. The API has the same package and class names on every version, `dev.compixel.forge` included; only the Minecraft and loader types around it differ.

CompixelUI is built with Kotlin 2.4.10 and Compose 1.12.0.

## Maven coordinates

| Coordinate | Contents |
| --- | --- |
| `dev.compixel:compixel-<loader>-<minecraft>` | The library; Gradle adds the Compose runtime it needs |

The loader is `forge` for 1.20.1 and `neoforge` for the others. Each version also publishes `sources`, `javadoc` and `development` (the F8 preview) classifiers. Add `google()` to `repositories` so Gradle can resolve the AndroidX compile dependencies declared by the runtime bundle.

### Forge 1.20.1

The 1.20.1 artifact uses SRG names, so add it through a remapping configuration, as with other Forge mods. In ModDevGradle Legacy, the `mod…` configurations don't bring transitive dependencies along, so also add the same coordinate through plain configurations for the Compose runtime and compile-time Kotlin libraries; the CompixelUI library that comes in this way is remapped too.

```groovy
dependencies {
    modCompileOnly "dev.compixel:compixel-forge-1.20.1:${compixel_version}"
    modLocalRuntime "dev.compixel:compixel-forge-1.20.1:${compixel_version}"
    compileOnly "dev.compixel:compixel-forge-1.20.1:${compixel_version}"
    localRuntime "dev.compixel:compixel-forge-1.20.1:${compixel_version}"

    additionalRuntimeClasspath "org.jetbrains.kotlin:kotlin-stdlib:2.4.10"
    additionalRuntimeClasspath "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    additionalRuntimeClasspath "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3"
}
```

`localRuntime`, `modLocalRuntime` and `additionalRuntimeClasspath` are the configurations that the ModDevGradle Legacy MDK sets up.

## Kotlin

CompixelUI requires Kotlin 2.2.21 or newer, along with compatible Coroutines and Serialization libraries.

- **Players:** install any compatible Kotlin runtime mod for your Minecraft version (for example, Kotlin for Forge). If a compatible runtime is missing or outdated, startup displays an error screen explaining what is needed.
- **Mod developers:** `compileOnly` resolves the Kotlin and AndroidX libraries needed to compile from Maven automatically; for `runClient`, add the standard Kotlin libraries (`kotlin-stdlib`, `kotlinx-coroutines-core-jvm`, `kotlinx-serialization-core-jvm`) via `additionalRuntimeClasspath` on 1.20.1/1.21.1 (or `localRuntime` on 26.x), or load a Kotlin runtime mod instead.

## Graphics

CompixelUI draws with the same graphics API as the game: OpenGL, or Vulkan when Minecraft 26.2 or 26.3 runs on Vulkan. Set `-Dcompixel.backend` to `opengl`, `vulkan` or `cpu` to override it; `cpu` is a slow reference renderer for troubleshooting.

Each file contains the native libraries for Windows, Linux and macOS on x64 and arm64.

## Loader differences

| | Forge 1.20.1 | NeoForge |
| --- | --- | --- |
| Metadata | `mods.toml`, `mandatory = true` | `neoforge.mods.toml`, `type = "required"` |
| Menu screens | `MenuScreens.register` in `enqueueWork` | `RegisterMenuScreensEvent` |
| HUD layers | `RegisterGuiOverlaysEvent` | `RegisterGuiLayersEvent` |
| Minecraft values in menu sync | `MinecraftSyncCodecs.buffer` | `MinecraftSyncCodecs.registry` |

## Keyboard and text input

- Letter and punctuation keys follow the player's keyboard layout. Text fields support selection and the clipboard.
- On 26.x, a focused text field opens Minecraft's text input like a vanilla edit box, and input method text is composed inside the field.
- On 1.20.1 and 1.21.1, the operating system's input method window shows text being composed.

## Other mods

Container screens keep the native container screen and its events, so recipe viewers and other container add-ons keep working. Widgets that other mods add to a screen draw above the Compose content and receive input first.
