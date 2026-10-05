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
    maven {
        url = 'https://maven.wintercogs.com/releases'
        content { includeGroup 'dev.compixel' }
    }
}

dependencies {
    compileOnly "dev.compixel:compixel-neoforge-1.21.1-with-kotlin:${compixel_version}"
    localRuntime "dev.compixel:compixel-neoforge-1.21.1-with-kotlin:${compixel_version}"
}

kotlin { jvmToolchain(21) }
```

And in `gradle.properties`:

```properties
compixel_version=0.1.6
kotlin.stdlib.default.dependency=false
```

The `-with-kotlin` coordinate brings the Kotlin libraries along. If your mod already depends on Kotlin for Forge, use `compixel-neoforge-1.21.1` on both lines instead and let KFF supply Kotlin.

CompixelUI is installed as its own mod, so don't include it in your JAR through Jar-in-Jar.

## 2. Declare the dependency

In `src/main/resources/META-INF/neoforge.mods.toml`, with your own mod ID:

```toml
[[dependencies.examplemod]]
modId = "compixel"
type = "required"
versionRange = "[0.1.6]"
ordering = "AFTER"
side = "CLIENT"
```

Use `side = "BOTH"` if you use [menu synchronization](menu-sync.md), which also runs on the server.

## 3. Open a screen

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.compixel.forge.ComposeScreen
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.layout.OreScreen
import net.minecraft.network.chat.Component

class CounterScreen : ComposeScreen<Int, Unit>(Component.literal("Counter")) {
    private var count = 0

    override fun snapshot() = count

    override fun handle(action: Unit) {
        count++
    }

    @Composable
    override fun Content(state: Int) {
        OreScreen("Counter", maxWidth = 160.dp, maxHeight = 78.dp) {
            OreText("Clicked $state times")
            OreButton("Click me", onClick = { send(Unit) })
        }
    }
}
```

![The counter screen in Minecraft after three clicks](../assets/counter-en.png)

Open it with `Minecraft.getInstance().setScreen(CounterScreen())` from client code, such as a key mapping handler. `ComposeScreen` is an ordinary Minecraft screen, and `OreScreen` draws the panel and its title. Keep UI code in client-only classes so a dedicated server never loads it.

The screen keeps its data on the game thread, while Compose draws on its own thread. `ComposeScreen<S, A>` connects the two through three members you override: `snapshot` reads the data into an immutable value of type `S`, `Content` draws the latest snapshot, and `handle` runs the actions of type `A` that buttons `send`. Here the count lives in the screen, and every click is an action.

## 4. Show game data

`snapshot` and `handle` run on the game thread, so they can use players, item stacks and other game objects. This screen shows the item in the player's main hand and drops one on a click:

```kotlin
data class HeldItem(val name: String, val count: Int)

class HandScreen : ComposeScreen<HeldItem, Unit>(Component.literal("Hand")) {
    override fun snapshot(): HeldItem {
        val stack = Minecraft.getInstance().player?.mainHandItem ?: ItemStack.EMPTY
        return HeldItem(stack.hoverName.string, stack.count)
    }

    override fun handle(action: Unit) {
        Minecraft.getInstance().player?.drop(false)
    }

    @Composable
    override fun Content(state: HeldItem) {
        OreScreen("Hand", maxWidth = 180.dp, maxHeight = 84.dp) {
            OreText("${state.name} × ${state.count}")
            OreButton("Drop one", onClick = { send(Unit) })
        }
    }
}
```

Open it with `Minecraft.getInstance().setScreen(HandScreen())`.

| Member | Runs |
| --- | --- |
| `snapshot()` | On the game thread when the screen opens, after each input event that sent actions, and every tick |
| `handle(action)` | On the game thread, once per action, before the next snapshot |
| `Content(state)` | On the Compose thread, with the latest snapshot |
| `send(action)` | Anywhere, usually in UI callbacks. Returns `false` while the screen is closed or 64 actions are waiting. |

A click, key or typed character that sends actions has them handled, and a new snapshot taken, before the event returns, just as vanilla buttons act at once; the next frame already shows the result. Actions sent at other times, for example from a coroutine, wait for the next tick. Because `snapshot()` can run more than once per tick, keep it to reading the game.

The screen takes the first snapshot before its first frame, so the UI never shows placeholder values. When it closes, actions not yet handled are dropped; when it opens again, it starts from a new snapshot. Call `requestClose()` to close it from a button; the screen closes before the click returns.

Return data classes or other immutable values from `snapshot()`: an equal snapshot redraws nothing. A screen without game state extends `ComposeScreen<Unit, Nothing>` with `override fun snapshot() {}` and `override fun handle(action: Nothing) {}`. `ComposeMenuScreen`, `ComposeInventoryScreen` and `ComposeHudLayer` work the same way for [menus and container screens](inventory.md#show-the-menus-state) and [HUD layers](hud.md).

In `Content`, use only `state`, `send` and values prepared in the constructor. Don't touch players, item stacks or other game objects inside composables, and never make Compose wait for the game thread.

## 5. Ship it

Players install CompixelUI next to your mod. Each Minecraft version has two files; players pick one:

| File | For |
| --- | --- |
| `compixel-neoforge-1.21.1-0.1.6-with-kotlin.jar` | Everyone; Kotlin included |
| `compixel-neoforge-1.21.1-0.1.6.jar` | Players who already have Kotlin for Forge |

Both are on the [Releases](https://github.com/Frostbite-time/CompixelUI/releases) page.

## Next steps

- [Ore UI](ore-ui.md): every control, with examples
- [Items and tooltips](items.md): real item icons in your UI
- [Container screens](inventory.md): menus with slots
- [HUD layers](hud.md): Compose over the game view
- [Menu synchronization](menu-sync.md): server state and client requests
