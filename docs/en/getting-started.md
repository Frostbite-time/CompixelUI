# Getting started

[简体中文](../zh-CN/getting-started.md) · [All guides](../README.md)

This guide adds CompixelUI to a NeoForge 1.21.1 mod and opens a first screen. Other Minecraft versions follow the same steps; [Compatibility](compatibility.md) lists what differs.

## 1. Add the dependency

In your mod's `build.gradle`:

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.4.10'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.4.10'
}

repositories {
    google()
    maven {
        url = 'https://maven.wintercogs.com/releases'
        content { includeGroup 'dev.compixel' }
    }
}

dependencies {
    compileOnly "dev.compixel:compixel-neoforge-1.21.1:${compixel_version}"
    localRuntime "dev.compixel:compixel-neoforge-1.21.1:${compixel_version}"

    // Standard Kotlin libraries for runClient (skip if you load a Kotlin runtime mod in dev).
    additionalRuntimeClasspath "org.jetbrains.kotlin:kotlin-stdlib:2.4.10"
    additionalRuntimeClasspath "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    additionalRuntimeClasspath "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3"
}

kotlin { jvmToolchain(21) }
```

And in `gradle.properties`, with the latest version from [Releases](https://github.com/Frostbite-time/CompixelUI/releases):

```properties
compixel_version=<version>
kotlin.stdlib.default.dependency=false
```

`compileOnly` brings in the Compose runtime, AndroidX compile dependencies (from `google()`) and Kotlin libraries automatically; `additionalRuntimeClasspath` supplies standard Kotlin libraries for `runClient` (if your mod already loads a Kotlin runtime mod in `localRuntime`, you can use that instead). CompixelUI is installed as its own mod, so don't include it in your JAR through Jar-in-Jar.

## 2. Declare the dependency

In `src/main/resources/META-INF/neoforge.mods.toml`, with your own mod ID and the same version:

```toml
[[dependencies.examplemod]]
modId = "compixel"
type = "required"
versionRange = "[<version>]"
ordering = "AFTER"
side = "CLIENT"
```

Use `side = "BOTH"` if you use [menu synchronization](menu-sync.md), which also runs on the server.

## 3. Open a screen

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import dev.compixel.forge.ComposeScreen
import dev.compixel.host.gameState
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.layout.OreScreen
import net.minecraft.network.chat.Component

class CounterScreen : ComposeScreen(Component.literal("Counter")) {
    private var count = 0

    @Composable
    override fun Content() {
        val clicks by gameState { count }
        OreScreen("Counter", maxWidth = 160.dp, maxHeight = 78.dp) {
            OreText("Clicked $clicks times")
            OreButton("Click me", onClick = { gameThread.post { count++ } })
        }
    }
}
```

![The counter screen in Minecraft after three clicks](../assets/counter-en.png)

Open it with `Minecraft.getInstance().setScreen(CounterScreen())` from client code, such as a key mapping handler. `ComposeScreen` is an ordinary Minecraft screen, and `OreScreen` draws the panel and its title. Keep UI code in client-only classes so a dedicated server never loads it.

The screen and its content run on the game thread, and Compose redraws only what changed. Call `gameState` inside `Content` or another composable: it reads the data immediately and then every tick. `gameThread.post` runs a block once Compose has handled the click. Here the count lives in the screen: a click adds one, and `clicks` shows the new count in the next frame.

## 4. Show game data

`gameState` blocks and posted blocks run on the game thread, so they can use players, item stacks and other game objects. This screen shows the item in the player's main hand and drops one on a click:

```kotlin
data class HeldItem(val name: String, val count: Int)

class HandScreen : ComposeScreen(Component.literal("Hand")) {
    @Composable
    override fun Content() {
        val held by gameState {
            val stack = Minecraft.getInstance().player?.mainHandItem ?: ItemStack.EMPTY
            HeldItem(stack.hoverName.string, stack.count)
        }
        OreScreen("Hand", maxWidth = 180.dp, maxHeight = 84.dp) {
            OreText("${held.name} × ${held.count}")
            OreButton("Drop one", onClick = { gameThread.post { Minecraft.getInstance().player?.drop(false) } })
        }
    }
}
```

Open it with `Minecraft.getInstance().setScreen(HandScreen())`.

| API | Runs |
| --- | --- |
| `val x by gameState(intervalTicks, keys) { … }` | Call it in a composable. Its block reads the first value immediately on the game thread, then every `intervalTicks` ticks (1 by default) and after posted work runs. Changing the keys or interval immediately reads a new value; leaving the composition ends the subscription. |
| `gameThread.post { … }` | Queues the block for the game thread, from anywhere, usually UI callbacks. Returns `false` while the screen is closed or 64 blocks are waiting. |
| `Content()` | On the game thread, when a value it reads changed |

A click, key or typed character that posts work has it run, and every `gameState` read again, before the event returns, just as vanilla buttons act at once; the next frame already shows the result. Work posted at other times, for example from a coroutine, runs at the next tick, which reads everything again too. A block posted by another block waits for the next round.

Reading a `gameState` property only takes the latest value and never runs its block; Compose redraws what reads it when the value changes. Return numbers, strings, data classes or other immutable values, never game objects such as entities, menus or item stacks: an equal value redraws nothing. Keep the blocks to reading the game, and read values that have to match in one block. Requests the server must handle still go through [menu synchronization](menu-sync.md) or your own packets, called inside a posted block.

Each `gameState` reads its first value as its content composes, so the first frame needs no placeholder. When the screen closes, work not yet run is dropped and the content keeps its last values for its exit; when it opens again, it reads them again. Call `requestClose()` to close it from a button; the screen closes before the click returns.

Use `gameState` for game data that changes: directly reading a player or item stack inside a composable does not tell Compose when it changes. Keep screen changes behind `gameThread.post` or `requestClose()` so they run after Compose finishes handling the callback. `ComposeMenuScreen`, `ComposeInventoryScreen` and `ComposeHudLayer` work the same way for [menus and container screens](inventory.md#show-the-menus-state) and [HUD layers](hud.md). Import `dev.compixel.host.gameState` and `androidx.compose.runtime.getValue` to use the delegate.

## 5. Ship it

Players install CompixelUI (from [Releases](https://github.com/Frostbite-time/CompixelUI/releases)) and a compatible Kotlin runtime mod (such as Kotlin for Forge) alongside your mod.

## Next steps

- [Ore UI](ore-ui.md): every control, with examples
- [Items and tooltips](items.md): real item icons in your UI
- [Container screens](inventory.md): menus with slots
- [Screen transitions](transitions.md): animate screens in and out
- [HUD layers](hud.md): Compose over the game view
- [Menu synchronization](menu-sync.md): server state and client requests
