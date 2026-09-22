# Build and test

[简体中文](../zh-CN/build-and-test.md) · [Documentation](../README.md)

Run commands from the repository root. Windows examples use PowerShell and `gradlew.bat`; on Linux/macOS use `bash gradlew` with the same task names and properties. The background game launchers described below are Windows-specific.

## Java and target selection

Gradle needs JDK 17 or newer; JDK 25 is recommended. Minecraft adapters need the Java versions in the [compatibility table](compatibility.md). Gradle discovers installed toolchains and can provision missing ones through Foojay. For nonstandard installations, set `COMPOSEMC_JDK17`, `COMPOSEMC_JDK21` and `COMPOSEMC_JDK25` to their respective JDK directories.

`-PcomposemcTargets=all` is the default. Select one target, a comma-separated list, or `none` to configure only shared modules:

```powershell
.\gradlew.bat '-PcomposemcTargets=none' checkCore
.\gradlew.bat '-PcomposemcTargets=1.21.1,26.3' buildAllMods
.\gradlew.bat buildAllMods
```

`checkCore` checks module boundaries, shared unit tests and offscreen desktop fixtures. `buildAllMods` builds and verifies all enabled adapters and their API artifacts. Compilation is not a substitute for real-client acceptance.

## IntelliJ IDEA

1. Open the repository root as a Gradle project and refresh the Gradle model. Choose an installed JDK for **Settings → Build Tools → Gradle → Gradle JVM**; adapter compiler toolchains are configured separately.
2. In **Run → Edit Configurations → + → Gradle**, select the root project as the Gradle project.
3. Put one of the following commands into **Tasks and arguments**, then run the configuration.

| Minecraft | Tasks and arguments |
| --- | --- |
| 1.20.1 | `:minecraft:forge-1.20.1:runClient -PcomposemcTargets=1.20.1` |
| 1.21.1 | `:minecraft:neoforge-1.21.1:runClient -PcomposemcTargets=1.21.1` |
| 26.1.2 | `:minecraft:neoforge-26.1.2:runClient -PcomposemcTargets=26.1.2` |
| 26.2 | `:minecraft:neoforge-26.2:runClient -PcomposemcTargets=26.2` |
| 26.3 | `:minecraft:neoforge-26.3:runClient -PcomposemcTargets=26.3` |

If the Gradle tool window omits the task tree, enable task-list building in IDEA's Gradle settings and reload. Its exact option text varies by IDEA version. Creating an explicit Gradle run configuration works without expanding that tree. A target omitted by `composemcTargets` will not appear until the project is reimported with it enabled.

Adapter `development` compilations are associated with `main` through Kotlin's `associateWith`, allowing previews and probes to access the adapter's `internal` declarations. Reload the Gradle project after build-convention changes so IDEA imports that visibility relationship. Passing compiler friend paths alone does not declare this source-set relationship to the IDE.

Renderer probes belong to `render-gl/src/testFixtures`, which can access `render-gl/main` internals. Adapters call the fixture's test entry point through a regular dependency; they do not access internal renderer classes across projects. Keep new probes with the module whose internal behavior they inspect, and include them only in development artifacts.

`runClient` includes the development preview. Press **F8** in the game to open it. For 26.2/26.3, append `-PcomposemcBackend=opengl` or `-PcomposemcBackend=vulkan`; `auto` follows the host backend. A Vulkan request on an unsupported target fails explicitly.

## Build artifacts

```powershell
.\gradlew.bat '-PcomposemcTargets=26.3' :minecraft:neoforge-26.3:build
```

Look in `minecraft/<loader>-<mc>/build/libs/`:

| Filename | Destination |
| --- | --- |
| `composemc-<loader>-<mc>-0.1.0-alpha.34.jar` | Standard installation; requires an external Kotlin provider |
| `…-with-kotlin.jar` | Alternative installation including Kotlin; install only one variant |
| `…-dev.jar` | Consumer compile classpath only |
| `…-development.jar` | Optional F8 preview/probe mod alongside the player JAR |
| `…-sources.jar` | Common project sources for standard, `with-kotlin` and `dev`; not an installable mod |
| `…-javadoc.jar` | Common Dokka HTML API reference; not an installable mod |

Use loader `forge` for 1.20.1 and `neoforge` otherwise. Both Forge installation variants are reobfuscated; named JARs under `build/devlibs` are development-launch artifacts. `build` produces all six artifacts in the table. Runtime bundles under `runtimes/` are internal outputs, not a second installable mod.

`sourcesJar` packages the selected adapter's production sources and its bundled shared modules, using readable development mappings even on Forge 1.20.1. It excludes other adapters, development probes and third-party sources. `javadocJar` packages Dokka 2.2.0 HTML with a bilingual overview and public declarations from the same source set. Member descriptions use existing KDoc/Javadoc comments; the bilingual guides remain the tutorial and usage reference.

For example, run `:minecraft:neoforge-1.21.1:sourcesJar` or `:minecraft:neoforge-1.21.1:javadocJar` separately. Open the HTML at `minecraft/neoforge-1.21.1/build/dokka/html/index.html`. Documentation generation is serialized across adapters to bound memory usage. `verifyApiArchives`, included in `check`, checks source contents against the checkout, representative shared/adapter pages, the bilingual overview and accidental local-path leakage.

Publish locally for a consumer with:

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:publishLibraryPublicationToConsumerRepository
```

The repository is `build/consumer-maven`. This task does not publish to an external Maven service. See [quick start](getting-started.md) for consumer dependencies.

Attachments use the ordinary `sources` and `javadoc` classifiers. A `:dev` consumer resolves both through Gradle's standard JVM artifact queries; the generated IDEA model associates them with the dev binary. No duplicate `dev-sources` or `dev-javadoc` artifacts are published. Enable source/documentation downloading in your IDE and refresh Gradle if attachments are not downloaded automatically.

## Desktop previews and documentation images

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :desktop:smoke
.\gradlew.bat '-PcomposemcTargets=none' :desktop:run
```

`smoke` renders the current fixtures offscreen into `desktop/build/screenshots`, including English/Chinese layouts and interaction states. It also checks for blank renders and bounded nested tooltips. `run` opens the interactive desktop preview. Desktop images establish component appearance, not Minecraft GPU compatibility.

The documentation assets use fresh unedited renders. See [asset sources](../assets/README.md) for the filenames to copy after capture. Keep image captions and both language pages in step with the rendered interface.

## Packaged game validation

Packaged benchmarks default to bundled Kotlin. To test an external provider, append `-KotlinMode external -KotlinProviderJar C:\path\to\kotlinforforge.jar` to either Windows benchmark script. Gradle packaged tasks accept `-PcomposemcKotlinMode=external -PcomposemcKotlinProviderJar=C:/path/to/kotlinforforge.jar`. The provider is staged only into the test game, never embedded in the release. Omit the provider in external mode to check the missing-runtime error. `runClient` remains a source development launch with its own bundled runtime.

Direct `runClient`, `runClientSmoke`, `runPackagedSmoke` and `runBenchmark` tasks use visible game windows. The smoke tasks can exercise the real clipboard and are for deliberate interactive runs. Unit tests and `:desktop:smoke` remain headless. The background wrapper explicitly enables `composemcBenchmarkBackground=true` and desktop isolation; setting the background flag alone is not a hidden launch.

Use the hidden Windows launcher for automated game runs. It creates a separate, never-activated desktop and does not send OS keyboard/mouse input or change the clipboard:

```powershell
.\tools\run_background_benchmark.ps1 -Minecraft 1.21.1 -Backend opengl -Label validation -Frames 120
.\tools\run_background_benchmark.ps1 -Minecraft 26.3 -Backend vulkan -Label validation -Frames 120
```

For Vulkan validation, also pass `-ValidationLayerPath C:\path\to\validation-layer`. Forge additionally has a production-launch check for the installable SRG-mapped JAR:

```powershell
.\tools\run_forge_production_benchmark.ps1 -JavaHome C:\path\to\jdk17 -Label validation
```

The benchmark installs built library/development JARs into an isolated game directory. Look under `minecraft/<adapter>/build/benchmark-<backend>-background-<label>/` for `composemc-benchmark.txt`, logs and captures. The 1.21.1 profiler writes PNG/CSV/JSON under `benchmark-results/`; other adapters have different report formats and scenario counts. The run must finish with `PASS`. The documented [26.2 upstream diagnostic](compatibility.md) is handled separately from unexpected validation errors.

The 1.21.1 profiler measures screen-render callbacks with warmup and forward/reverse repetitions. Its CPU spans are wall time; GPU timestamps are asynchronous intervals tied to the originating frame. Do not add CPU and GPU times, treat missing samples as zero, or present these numbers as whole-game FPS. `-Dcomposemc.profile=true` enables frame history; `-Dcomposemc.allocations=true` adds supported JVM allocation measurements. Read `frameProfiler` on the client thread.

For standalone synchronization diagnostics:

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:check :slot-core:check
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:profileSyncCollection :menu-sync:profileSyncMap
```

Profile outputs are in `menu-sync/build/profiles`. They measure JVM snapshot/protocol work, excluding Minecraft, network latency and rendering; timing is diagnostic rather than a pass/fail gate.

## CI and change checks

[CI](../../.github/workflows/verify.yml) checks the shared core on Windows/Linux and builds all five adapters on Linux. Real GPU validation runs separately on a suitable host.

For dependency/module-boundary changes, run `verifyCoreBoundary` and the affected tests/builds. For shared public API changes, compile all affected adapters and a separate consumer. Rendering or native-lifecycle changes also require packaged clients for affected versions/backends.

Run `node tools/check_docs.mjs` after documentation edits to check local links, anchors, bilingual pairing and image references. Generated `build/`, `.gradle/` and `.work/` content is disposable; preserve source, Gradle wrapper files, target metadata and dependency lockfiles.
