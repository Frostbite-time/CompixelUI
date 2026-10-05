# Contributing

[简体中文](../zh-CN/contributing.md) · [All guides](../README.md)

How to build CompixelUI from source, try it in game and check a change. Run commands from the repository root. The examples use Windows PowerShell; on Linux and macOS, run `bash gradlew` with the same arguments.

## Set up

- Gradle needs JDK 17 or newer; JDK 25 is recommended. The Minecraft versions also use JDK 17, 21 and 25. Gradle finds installed JDKs and downloads missing ones; for unusual locations, set `COMPIXEL_JDK17`, `COMPIXEL_JDK21` and `COMPIXEL_JDK25`.
- In IntelliJ IDEA, open the repository as a Gradle project.

## Build

```powershell
.\gradlew.bat buildAllMods
.\gradlew.bat '-PcompixelTargets=1.21.1,26.3' buildAllMods
.\gradlew.bat '-PcompixelTargets=none' checkCore
```

`-PcompixelTargets` selects Minecraft versions: `all` (the default), `none` for the shared code only, or a comma-separated list. Each version's output is in `minecraft/<loader>-<version>/build/`:

| File | Contents |
| --- | --- |
| `release/compixel-…-with-kotlin.jar`, `release/compixel-….jar` | The two player files |
| `libs/compixel-….jar` | The Maven library, which needs its runtime from Maven |
| `libs/…-development.jar` | The F8 component preview mod |
| `libs/…-sources.jar`, `libs/…-javadoc.jar` | Sources and API reference |

To try a local build in your own mod, publish it to `build/consumer-maven` and add that folder as a Maven repository:

```powershell
.\gradlew.bat '-PcompixelTargets=1.21.1' :minecraft:neoforge-1.21.1:publishAllPublicationsToConsumerRepository
```

## Run the game

| Minecraft | Gradle task and arguments |
| --- | --- |
| 1.20.1 | `:minecraft:forge-1.20.1:runClient -PcompixelTargets=1.20.1` |
| 1.21.1 | `:minecraft:neoforge-1.21.1:runClient -PcompixelTargets=1.21.1` |
| 26.1.2 | `:minecraft:neoforge-26.1.2:runClient -PcompixelTargets=26.1.2` |
| 26.2 | `:minecraft:neoforge-26.2:runClient -PcompixelTargets=26.2` |
| 26.3 | `:minecraft:neoforge-26.3:runClient -PcompixelTargets=26.3` |

In IDEA, create a Gradle run configuration with one of these as **Tasks and arguments**. On 26.2 and 26.3, add `-PcompixelBackend=vulkan` or `-PcompixelBackend=opengl` to pick the renderer.

Press **F8** in game to open the component preview, which shows every Ore UI control:

![The F8 component preview](../assets/preview-en.png)

## Test

- `checkCore` checks module boundaries and formatting, and runs the unit tests and offscreen UI tests.
- `:desktop:run` opens the component preview in a desktop window, and `:desktop:smoke` renders it to `desktop/build/screenshots`.
- Two client suites run in a real game with a fresh test world. `runAcceptance` checks rendering, input, items, containers, menu sync, the HUD, resizing, resource reloads and cleanup; `runBenchmark` measures frame times.

```powershell
.\gradlew.bat '-PcompixelTargets=1.21.1' :minecraft:neoforge-1.21.1:runAcceptance
.\gradlew.bat '-PcompixelTargets=26.3' :minecraft:neoforge-26.3:runBenchmark '-PcompixelBackend=vulkan'
```

A suite passes when its report, `compixel-acceptance.txt` or `compixel-benchmark.txt` in the run's game directory, starts with `PASS`. On Windows, `tools\run_background_acceptance.ps1` and `tools\run_background_benchmark.ps1` run every version on a separate hidden desktop, so the game never covers your screen or takes focus. `python tools/summarize_benchmarks.py` compares benchmark reports.

The Forge 1.20.1 player file is remapped to SRG names, so `tools\run_forge_production.ps1` tests it in a real Forge installation.

## Before you commit

- Run `.\gradlew.bat spotlessApply` to format Kotlin, Java and Gradle files.
- After changing dependencies or modules, run `verifyCoreBoundary`.
- Each Minecraft version has its own copy of the game-facing code. Apply a fix to every affected version and test each one.
- The client suite drivers are identical in every version; `verifySuiteParity` keeps them in step.
- If you change what a runtime bundle contains, such as the Compose, Skiko or Kotlin version, raise its `runtime_*_version` in `gradle.properties` and run `:runtime-<name>:writeBundleLock`.
- After editing the documentation, run `node tools/check_docs.mjs`.
