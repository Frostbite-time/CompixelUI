# Quick start

[简体中文](../zh-CN/getting-started.md) · [Documentation](../README.md)

This guide connects an existing **NeoForge 1.21.1 Kotlin mod** to Compose MC. It uses Java 21, Kotlin/Compose compiler 2.4.10 and library version 0.1.0-alpha.34. See [compatibility](compatibility.md) for other targets.

Already integrated an earlier alpha? Follow [consumer upgrade notes](compatibility.md#upgrade-an-existing-consumer) for the request-result API, transport options and protocol version before rebuilding.

## 1. Build the library

From the Compose MC repository, run:

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:build :minecraft:neoforge-1.21.1:publishAllPublicationsToConsumerRepository
```

This creates a local Maven repository at `build/consumer-maven` with the library, its `-with-kotlin` coordinate and the runtime bundles they depend on. The setup below uses that repository; it does not assume that this version is available on Maven Central. [Build and test](build-and-test.md) covers toolchain setup and other operating systems.

## 2. Add consumer dependencies

Merge these declarations into the consumer's existing Groovy `build.gradle`. Keep its loader plugin and Minecraft configuration. `localRuntime` below is supplied by NeoForge ModDevGradle.

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.4.10'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.4.10'
}
repositories {
    maven {
        url = uri('../compose-mc/build/consumer-maven')
        content { includeGroup 'dev.composemc' }
    }
}
dependencies {
    compileOnly "dev.composemc:composemc-neoforge-1.21.1-with-kotlin:${composemc_version}"
    localRuntime "dev.composemc:composemc-neoforge-1.21.1-with-kotlin:${composemc_version}"
}
kotlin { jvmToolchain(21) }
```

Adjust the repository path to your checkout. In the consumer's `gradle.properties`:

```properties
composemc_version=0.1.0-alpha.34
kotlin.stdlib.default.dependency=false
```

The `-with-kotlin` coordinate resolves the library mod, `composemc-runtime-standard` with Compose and Skiko, and `composemc-kotlin` with the Kotlin libraries. A development launch loads both bundles as libraries beside the mod, so no external Kotlin provider is needed. If your mod already depends on Kotlin for Forge, use `composemc-neoforge-1.21.1` without `-with-kotlin` on both lines: KFF's dependency supplies Kotlin for compilation and the development launch. If KFF only joins the development launch, as described below, keep the `-with-kotlin` coordinate for `compileOnly`, which supplies the Kotlin API, and use the plain coordinate for `localRuntime`. Do not install both variants or combine `with-kotlin` with KFF. Keep the Kotlin and Compose compiler plugins on the same version; do not embed these runtimes into the consumer.

To include KFF in a NeoForge 1.21.1 development launch, add `maven { url = 'https://api.modrinth.com/maven' }` to repositories and `localRuntime 'maven.modrinth:kotlin-for-forge:5.12.0'` to dependencies. This is a development runtime dependency, not a JarJar dependency. See [compatibility](compatibility.md#kotlin-runtime-providers) for tested providers and runtime requirements.

Declare the dependency in `META-INF/neoforge.mods.toml`, replacing `your_mod_id`:

```toml
[[dependencies.your_mod_id]]
modId="composemc"
type="required"
versionRange="[0.1.0-alpha.34]"
ordering="AFTER"
side="CLIENT"
```

Use `side="BOTH"` if you use menu synchronization or common slot operations. Generate the version range from the same Gradle property when processing your metadata template. Forge 1.20.1 uses `META-INF/mods.toml` and `mandatory=true` instead of `type="required"`.

## 3. Open a screen

Place this file in the consumer's client code. Call `openCounterScreen()` from a client-thread event or key-binding handler. The host installs `OreTheme` and game click feedback. Compose MC hosts draw no Minecraft menu background, blur or dimming on any target; `OreScreen` supplies its own backdrop.

```kotlin
import androidx.compose.runtime.*
import dev.composemc.forge.ComposeScreen
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.layout.OreScreen
import dev.composemc.ui.ore.theme.OreTheme
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

fun openCounterScreen() {
    val minecraft = Minecraft.getInstance()
    val parent = minecraft.screen
    minecraft.setScreen(ComposeScreen(
        title = Component.literal("Counter"),
        parent = parent,
    ) {
        var count by remember { mutableStateOf(0) }
        OreScreen("Counter") {
            OreText("Count: $count")
            OreButton("Add one", onClick = { count++ })
        }
    })
}
```

The counter is UI-local state. Game objects are captured before composition; callbacks that change game state need the bridge below. Keep screen construction and registration in client-only entry points so dedicated servers never load UI classes.

## 4. Connect game state

`dev.composemc.host.UiBinding<S, A>` transfers immutable snapshots into Compose and typed actions back to its creating thread:

| Operation | Where to call it |
| --- | --- |
| Construct `UiBinding(initialSnapshot)` | Client game thread |
| Read `binding.value` | Inside a composable |
| `binding.send(action)` | UI callback |
| `binding.drainActions(handler)` then `binding.update(snapshot)` | Game-thread tick |
| `binding.close()` | Game thread, when the logical owner closes |

`send` returns false when closed or when its bounded queue is full (64 entries by default). Handle rejection where an action matters. Equal snapshots do not trigger a new publication. Keep values immutable and reuse unchanged collections. UI-local search, focus and popup visibility can remain in Compose state.

Do not read live menus, `ItemStack` or `Minecraft` from a composable. Do not synchronously wait for the game thread from Compose: the game thread may already be waiting for composition. A [temporary recipe-screen visit](inventory.md) is different from finally closing a menu binding.

## 5. Install and preview

| Artifact | Use |
| --- | --- |
| `build/release/composemc-neoforge-1.21.1-0.1.0-alpha.34.jar` | Standard: install beside the consumer and a compatible external Kotlin provider |
| `build/release/…-with-kotlin.jar` | Alternative installation: includes Kotlin; no external provider needed |
| `build/libs/…-development.jar` | Optional F8 preview/probe mod; requires the normal library |
| `build/libs/…-sources.jar` / `…-javadoc.jar` | Source/API documentation attachments of the Maven library |

Install exactly one release variant from the adapter's `build/release/` beside the consumer; add the external provider when using the standard JAR. Both variants have the same mod ID and API and embed the Compose/Skiko runtime. `build/libs/` holds the Maven artifacts: its library JAR leaves the runtime to Gradle and does not start on its own. The development artifact is useful when inspecting controls; it is not a second runtime.

Source and documentation JARs are published by the same Maven task. They are IDE attachments, not runtime dependencies or mods. Enable source/documentation downloading in IDEA and refresh Gradle to browse Compose MC's own sources and read its generated API reference. The runtime bundles attach only a README listing their upstream libraries, which keep their own sources and documentation.

Continue with [Ore UI](ore-ui.md), [native items](native-content.md), [HUD layers](hud.md), [inventory](inventory.md) or [server menu synchronization](menu-sync.md).
