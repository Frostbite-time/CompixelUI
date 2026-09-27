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

Common renderer scenes, CPU reference pixels and preview exercises live in `testing`. The shared native-item visual scene and pixel assertions also live there; every adapter runs it against a packaged client to check opacity, rotation, shape clipping, occlusion and repeated placement. Backend probes belong to `render-gl/src/testFixtures` and `render-vulkan/src/testFixtures`; version-specific device setup and screen checks are grouped in each adapter's `development/render` package. The selected GPU probe runs at the start of both client suites, comparing both viewport sizes over repeated render/reset/close cycles. Vulkan validation layers additionally check API and synchronization errors. All test helpers stay out of release and consumer API artifacts.

`runClient` includes the development preview. Press **F8** in the game to open it. For 26.2/26.3, append `-PcomposemcBackend=opengl` or `-PcomposemcBackend=vulkan`; `auto` follows the host backend. A Vulkan request on an unsupported target fails explicitly.

## Build artifacts

```powershell
.\gradlew.bat '-PcomposemcTargets=26.3' :minecraft:neoforge-26.3:build
```

Players install a JAR from `minecraft/<loader>-<mc>/build/release/`; Maven consumers get the artifacts in `build/libs/`:

| Filename | Destination |
| --- | --- |
| `release/composemc-<loader>-<mc>-0.1.0-alpha.35.jar` | Standard installation; requires an external Kotlin provider |
| `release/…-with-kotlin.jar` | Alternative installation including Kotlin; install only one variant |
| `libs/composemc-<loader>-<mc>-0.1.0-alpha.35.jar` | Maven library JAR without the runtime; not an installable mod |
| `libs/…-development.jar` | Optional F8 preview/probe mod alongside the player JAR |
| `libs/…-sources.jar` | Project sources of the library JAR; not an installable mod |
| `libs/…-javadoc.jar` | Dokka HTML API reference; not an installable mod |

Use loader `forge` for 1.20.1 and `neoforge` otherwise. On Forge the JARs in `build/release` and `build/libs` are reobfuscated; named JARs under `build/devlibs` are development-launch artifacts. `build` produces all six artifacts in the table.

The release JARs embed runtime bundles that `runtimes/` builds with their own versions, set as `runtime_<name>_version` in the root `gradle.properties`: `composemc-runtime-standard` or `composemc-runtime-vulkan` with Compose and Skiko, plus `composemc-kotlin` in the `with-kotlin` JAR. The Maven library JAR contains the same Compose MC classes and declares its runtime bundle as a dependency; its `-with-kotlin` coordinate is a POM that adds `composemc-kotlin`. `verifyReleaseIsolation` checks that each release JAR equals the library JAR apart from its embedded bundles. Each bundle's `bundle.lock` records the dependencies of its version. Published versions cannot change, so when they change, raise the version and run `:runtime-<name>:writeBundleLock`.

`sourcesJar` packages the selected adapter's production sources and its bundled shared modules, using readable development mappings even on Forge 1.20.1. It excludes other adapters, development probes and third-party sources. `javadocJar` packages Dokka 2.2.0 HTML with a bilingual overview and public declarations from the same source set. Member descriptions use existing KDoc/Javadoc comments; the bilingual guides remain the tutorial and usage reference.

For example, run `:minecraft:neoforge-1.21.1:sourcesJar` or `:minecraft:neoforge-1.21.1:javadocJar` separately. Open the HTML at `minecraft/neoforge-1.21.1/build/dokka/html/index.html`. Documentation generation is serialized across adapters to bound memory usage. `verifyApiArchives`, included in `check`, checks source contents against the checkout, representative shared/adapter pages, the bilingual overview and accidental local-path leakage.

Publish locally for a consumer with:

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:publishAllPublicationsToConsumerRepository
```

The repository is `build/consumer-maven`. The task also publishes the runtime bundles that the POMs name. It does not publish to an external Maven service. See [quick start](getting-started.md) for consumer dependencies.

Every JAR has the ordinary `sources` and `javadoc` attachments. For a runtime bundle, these hold what its libraries publish: their sources merged into one tree, and each library's API documentation in its own directory. A README in each attachment lists the libraries and those published without one. Enable source/documentation downloading in your IDE and refresh Gradle if attachments are not downloaded automatically.

## Publish a release

Releases go to the Wintercogs Maven, `https://maven.wintercogs.com/releases`, through the **Publish to the Wintercogs Maven** GitHub workflow. Start it by hand from the Actions tab. Its publishing jobs use the `maven-publish` environment. Add yourself there as a required reviewer and store the environment secrets `REPOSILITE_TOKEN_NAME` and `REPOSILITE_TOKEN_SECRET`: a Reposilite access token with write access to `/releases`. A job cannot read those secrets until a reviewer approves it. On the GitHub Free, Pro and Team plans, required reviewers work only in public repositories, and environment secrets in a private repository need Pro or above.

1. Raise `mod_version` in `gradle.properties` and push.
2. A first job checks the repository without secrets. It fails if an adapter version is already published, so a release that cannot succeed never asks for approval, and it lists the runtime bundle versions the repository lacks.
3. If a bundle version is new, the bundle job waits for approval, verifies the bundle and publishes it. A published bundle version is never replaced.
4. The five Minecraft target jobs then wait for approval together, and one review releases them all. Each builds and verifies its adapter before publishing the library and its `-with-kotlin` POM.

A target job that fails after uploading part of its version leaves that version incomplete. Delete it in Reposilite, then rerun the failed job, which checks the version again. Uploads pass through Cloudflare, whose per-request limit (100 MB on the smaller plans) caps a bundle's size; the largest is about 75 MB. The repository name `wintercogs` gives Gradle the credential properties `wintercogsUsername` and `wintercogsPassword`; the workflow sets them from the secrets.

## Desktop previews and documentation images

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :desktop:smoke
.\gradlew.bat '-PcomposemcTargets=none' :desktop:run
```

`smoke` renders the current fixtures offscreen into `desktop/build/screenshots`, including English/Chinese layouts and interaction states. It also checks for blank renders and bounded nested tooltips. `run` opens the interactive desktop preview. Desktop images establish component appearance, not Minecraft GPU compatibility.

The documentation assets use fresh unedited renders. See [asset sources](../assets/README.md) for the filenames to copy after capture. Keep image captions and both language pages in step with the rendered interface.

## Client suites

Every target runs two automated suites in a real Minecraft client: **acceptance** checks correctness and **benchmark** measures performance. Both load the packaged mod archive together with the separate development archive: the release archive itself on NeoForge, and on Forge 1.20.1 the same archive before SRG remapping (see [Forge 1.20.1 production launch](#forge-1201-production-launch)). Both first create a fresh flat creative test world, `saves/composemc-<suite>`, replaced on every run, with render distance 2, sound muted and vsync off. Input reaches screens through real Screen callbacks with logical window focus, so neither suite reads, needs or takes OS focus, and hidden and visible runs execute the same frames. The suites never touch the system clipboard.

The five targets run identical suites. The drivers in each adapter's `development` package (`SuiteEnvironment`, `SuiteSession`, `ClientAcceptanceProbe`, `PreviewAcceptance`, `ClientBenchmarkProbe` and `NativeTooltipProbe`) are byte-identical copies; version differences live in that adapter's `SuitePlatform.kt` and its version-specific fixtures. `verifySuiteParity`, part of `checkCore`, rejects a drifted copy, a missing fixture or a wrong target name. The ordered step list and the benchmark protocol live in `testing`. A report can say `PASS` only after every step, or every scheduled case, completed in order.

| Task | Game directory under `minecraft/<adapter>/build/` | Output |
| --- | --- | --- |
| `runAcceptance` | `acceptance-<backend>-<window>/` | `composemc-acceptance.txt`; captures in `acceptance-results/` |
| `runBenchmark` | `benchmark-<backend>-<window>-<label>/` | `composemc-benchmark.txt`; `benchmark-results/report.json` and one CSV and PNG per case |

`<window>` is `foreground` or `background`. A task fails unless its report starts with `PASS`; a failed report names the step and includes the stack trace.

### Run visibly with Gradle

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:runAcceptance
.\gradlew.bat '-PcomposemcTargets=26.3' :minecraft:neoforge-26.3:runBenchmark '-PcomposemcBackend=vulkan' '-PcomposemcLabel=candidate'
```

Use `forge-1.20.1` for 1.20.1. A visible run shows the game window but still uses logical focus, so other windows can stay in front. Optional properties:

| Property | Default | Meaning |
| --- | --- | --- |
| `composemcBackend` | `auto` | `opengl`, `vulkan` (26.2/26.3) or `cpu` |
| `composemcLabel` | `baseline` | Benchmark directory and report label |
| `composemcBenchmarkFrames` | `360` | Measured frames per case, 120–1500 |
| `composemcBenchmarkRepeats` | `2` | Repetitions, 1–5, alternating forward and reverse case order |
| `composemcBenchmarkControl` | `false` | The same startup, world and resource reload with no Compose renderer |
| `composemcKotlinMode` | `bundled` | `external` installs the release JAR without Kotlin; add `composemcKotlinProviderJar=<jar>` to stage a provider, or omit it to check the missing-runtime error |
| `composemcVulkanValidation` | `false` | Enables validation layers and rejects unexpected `VUID-`/`SYNC-HAZARD` messages |

A provider is staged only into the test game, never embedded in the release. `composemcBackground=true` hides the window after startup, but Gradle refuses it unless `composemcIsolatedDesktop=true` is also set, because the window would still appear on the user's desktop first. The Windows scripts below set both. `runClient` remains a source development launch with its own bundled runtime.

### Run hidden on Windows

```powershell
.\tools\run_background_acceptance.ps1
.\tools\run_background_benchmark.ps1
.\tools\run_background_acceptance.ps1 -Minecraft 26.2,26.3 -Backend vulkan
.\tools\run_background_benchmark.ps1 -Minecraft 1.21.1 -Label candidate -Frames 600 -Repeats 3
```

Both scripts default to every target and run the versions one at a time, each in a fresh Gradle process on a separate, never-activated Win32 desktop started by [run_isolated_gradle.ps1](../../tools/run_isolated_gradle.ps1). The game never appears on, takes focus from, or plays sound on the interactive desktop, and no OS keyboard or mouse input is sent. A failed version does not stop the others: the script prints a PASS/FAIL table and exits with an error when any version failed. Logs are in `.work/suites/`. Both wrappers call [run_background_suite.ps1](../../tools/run_background_suite.ps1) with `-Suite acceptance` or `-Suite benchmark`; it also accepts `-KotlinMode external -KotlinProviderJar <jar>` and `-ValidationLayerPath <directory>`. With `-Backend vulkan`, targets without Vulkan are skipped.

### Acceptance coverage

Every target passes these steps, in this order, as listed in [ClientSuites.kt](../../testing/src/main/kotlin/dev/composemc/testing/suite/ClientSuites.kt):

1. Library and development translations, and the loader's production or development mode.
2. The selected renderer backend against CPU reference pixels at two viewport sizes, over repeated render/reset/close cycles.
3. The fresh test world.
4. A native container: left and right clicks, the per-slot render hook, server acknowledgement and screen release.
5. Menu synchronization on a server-opened menu: a bounded multi-batch snapshot, a fragmented action round trip and a native item sent both ways through one shared codec.
6. Config editing: staging, scalar and list validation, save and restore.
7. A pixel fixture: top-left pointer coordinates, premultiplied alpha, Unicode text and shortcut keys; then GUI scale 3, a framebuffer resize and a resource reload.
8. The native-item visual scene: opacity, rotation, shape clipping, occlusion and repeated placement.
9. A HUD layer over the game view: Compose and native item pixels, drawing on beneath a screen that takes the input, hiding with the GUI, GUI scale, a framebuffer resize and a resource reload in the same session, then release when the player leaves the world or on `close()`, and a new session on the next frame.
10. The real F8 preview, opened through its key mapping: retained frames, text input, modal Escape priority, resize and GUI scale, 100k-row list hit testing and scrolling, native item pixels with Minecraft drawing after Compose, the native tooltip sequence (delay, replacement, rich bundle image, cancellation, dismissal, modal suppression, edge placement, GUI scales and reload), 10k-row native scrolling, resource reload, logical focus loss and return, close, reopen, every Ore component page and 12 open/close cycles.

Every wait has a time limit, and each replaced Compose screen or closed HUD layer must release its session, surfaces and native images.

### Benchmark protocol

Every target measures the same [BenchmarkPlan](../../testing/src/main/kotlin/dev/composemc/testing/suite/BenchmarkPlan.kt): a 1280×960 framebuffer at GUI scale 2, a 60 FPS frame limit with vsync off and 2,400 pipeline warmup frames, then for each case 120 warmup frames, the measured frames and 16 GPU drain frames. The cases cover static UI, animation, 1k/10k/100k-row lists, static and scrolling native icons, 256 distinct animated native icons, a rich tooltip, a static and an animated HUD layer over the game view, and every Ore component page. A case is rejected rather than reported when its samples are incomplete, a static fixture repaints, an animated one stalls, icons starve or the tooltip loses hover.

CPU spans are wall time inside the screen render callback, or inside the HUD layer for the HUD cases. GPU values are asynchronous command-stream intervals joined to the originating frame by ID, without blocking reads. Do not add CPU and GPU times, treat missing samples as zero, or present these numbers as whole-game FPS. Outside the suite, `-Dcomposemc.profile=true` enables frame history and `-Dcomposemc.allocations=true` adds supported JVM allocation measurements; read `frameProfiler` on the client thread.

Compare reports from the same machine and protocol with the summarizer. One report prints its case table; several print one column per report with the mean change against the first, for version-to-version or before/after comparisons:

```powershell
python tools/summarize_benchmarks.py minecraft/neoforge-1.21.1/build/benchmark-opengl-background-baseline/benchmark-results/report.json minecraft/neoforge-26.3/build/benchmark-opengl-background-baseline/benchmark-results/report.json
```

The documented [26.2 upstream diagnostic](compatibility.md) is accepted separately from unexpected validation errors.

### Forge 1.20.1 production launch

Forge 1.20.1 is the only target whose release archive is remapped (SRG) for production, so its Gradle runs cannot load the file players install. This script installs Forge and runs either suite against that archive; the NeoForge targets' Gradle runs already load their release archives:

```powershell
.\tools\run_forge_production.ps1 -JavaHome C:\path\to\jdk17 -Suite acceptance
.\tools\run_forge_production.ps1 -JavaHome C:\path\to\jdk17 -Suite benchmark -Label production
```

For standalone synchronization diagnostics:

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:check :slot-core:check
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:profileSyncCollection :menu-sync:profileSyncMap
```

Profile outputs are in `menu-sync/build/profiles`. They measure JVM snapshot/protocol work, excluding Minecraft, network latency and rendering; timing is diagnostic rather than a pass/fail gate.

## CI and change checks

[CI](../../.github/workflows/verify.yml) checks the shared core on Windows/Linux and builds all five adapters on Linux. Real GPU validation runs separately on a suitable host.

Spotless enforces formatting: ktfmt in the Kotlin coding-conventions style (120 columns) for Kotlin sources and Kotlin build scripts, palantir-java-format for Java, and whitespace rules for Groovy build scripts. Run `.\gradlew.bat spotlessApply` before committing. The CI task `checkCore` runs `spotlessCheck`; `check` and `build` skip it because formatting the whole repository is slow. `.editorconfig` gives editors the same indentation and line length, and the ktfmt and palantir-java-format IntelliJ plugins reproduce the formatter exactly. `.git-blame-ignore-revs` lists formatting-only commits; run `git config blame.ignoreRevsFile .git-blame-ignore-revs` so local blame skips them.

For dependency/module-boundary changes, run `verifyCoreBoundary` and the affected tests/builds. For shared public API changes, compile all affected adapters and a separate consumer. Rendering or native-lifecycle changes also require both client suites for affected versions/backends. A change to a suite driver goes into all five copies at once; `verifySuiteParity` fails otherwise.

Run `node tools/check_docs.mjs` after documentation edits to check local links, anchors, bilingual pairing and image references. Generated `build/`, `.gradle/` and `.work/` content is disposable; preserve source, Gradle wrapper files, target metadata and dependency lockfiles.
