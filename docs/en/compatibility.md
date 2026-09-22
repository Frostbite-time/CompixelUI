# Compatibility

[简体中文](../zh-CN/compatibility.md) · [Documentation](../README.md)

This page describes the **0.1.0-alpha.34** build baseline. Each adapter under [minecraft](../../minecraft) owns its build settings in `build.gradle` and `gradle.properties`; the [target index](../../gradle/minecraft-targets.properties) selects directories, and the [version catalog](../../gradle/libs.versions.toml) owns shared dependencies. Rebuild consumers when adopting an alpha release with API changes.

## Targets

| Minecraft | Loader | Adapter Java | Runtime | Graphics |
| --- | --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.23 | 17 | Skiko 0.150.1 | OpenGL |
| 1.21.1 | NeoForge 21.1.250 | 21 | Skiko 0.150.1 | OpenGL |
| 26.1.2 | NeoForge 26.1.2.109 | 25 | Skiko 0.150.1 | OpenGL |
| 26.2 | NeoForge 26.2.0.88 | 25 | Polyfrost Skiko 0.999.6 | OpenGL / Vulkan |
| 26.3 | NeoForge 26.3.0.6-beta | 25 | Polyfrost Skiko 0.999.6 | OpenGL / Vulkan |

Shared modules target Java 17. Each adapter is compiled for its own Minecraft/loader APIs; one target's mod JAR cannot be installed on another target. Consumer Maven coordinates are `dev.composemc:composemc-<loader>-<minecraft>:<version>`.

Kotlin and the Compose compiler are 2.4.10; Compose is 1.12.0. Every target offers two installation variants with the same mod ID and API. Do not mix standard and Vulkan graphics profiles or embed Compose MC into a consumer.

## Kotlin runtime providers

| Installation | Kotlin libraries | External provider |
| --- | --- | --- |
| Standard `composemc-…-0.1.0-alpha.34.jar` | Not included | Compatible stdlib, Coroutines Core and Serialization Core; KFF recommended |
| `composemc-…-0.1.0-alpha.34-with-kotlin.jar` | Included | Do not combine with KFF or another Kotlin runtime |

Install exactly one variant. Both include Compose, Skiko, atomicfu and the Swing dispatcher integration; neither embeds KFF. The standard JAR checks the client runtime instead of requiring the `kotlinforforge` mod ID. Stdlib must be at least 2.2.21 and Coroutines/Serialization must supply compatible APIs. Checking classes and representative methods cannot certify every third-party combination.

Provider baselines: KFF 4.12.0 for Forge 1.20.1, KFF 5.12.0 for NeoForge 1.21.1, KFF 6.3.0 for 26.1.2/26.2. They supply Coroutines 1.10.2 or 1.11.0 with matched Serialization libraries. KFF 6.3.0 excludes 26.3: use `with-kotlin` or a compatible independent provider. Both variants are built for 26.3 without special packaging logic; recognizing a future provider does not itself require a new Compose MC release.

The `dev` JAR is the full compile-only API bundle for either variant. The library's Java-only server menu/slot entry path does not require or initialize Kotlin/UI; consumers may impose additional requirements.

## Upgrade an existing consumer

Ore UI now uses responsibility-based subpackages. Replace root-package imports using the [component/package table](ore-ui.md#choose-a-component), then rebuild the consumer and install the matching library. This changes JVM names as well as source imports; an already compiled consumer needs recompilation.

Alpha.33 uses menu protocol **5**. Rebuild the consumer against the matching `dev` artifact and upgrade the installed library on both sides together. Protocol 4 peers and the previous state-record framing are incompatible.

| Integration point | Current API |
| --- | --- |
| `MenuSync.bind` configuration | Pass `MenuSyncOptions`; use `.withState(SyncLimits)` and `.withActions(ActionLimits)` for overrides. |
| `SyncLimits` transmission setting | Pass `TransferBudget(refill, capacity, peak)` as the second component. `TransferBudget.steady(n)` gives a fixed per-tick budget without accumulated bursts. |
| `MenuSync.request` result | Handle `ActionSubmission.queued()`, `failure()`, `actual()` and `limit()`. Use `onActionResult` for final execution results. |
| Action size constants | `MenuAction.DEFAULT_MAX_BYTES` names the 8 KiB default. Declare the desired `maximumBytes` explicitly; there is no `MAX_FRAGMENTED_BYTES` constant. |
| Custom core transport | Supply the transport cap to `SyncBatch.read(input, maximumBatchBytes)`. State field indexes use 32-bit framing. |

State/action policies and action declarations must match on both sides. The client confirms its menu attachment and policy before state bodies are sent, adding one opening confirmation round trip. See [menu synchronization](menu-sync.md) for all defaults, refusal handling and burst behavior.

## Rendering

Default rendering follows Minecraft's selected backend on 26.2/26.3. Earlier targets use OpenGL. Native GPU paths avoid full-frame pixel readback, PNG conversion and CPU upload. OpenGL supports the 3.2 context used by vanilla 1.20.1; optional queries and state handling follow host capabilities.

`-Dcomposemc.backend=cpu` selects a diagnostic reference renderer. It is useful for investigating pixels, not representative of normal GPU performance. Backend selection during development launches is covered in [build and test](build-and-test.md).

Each normal JAR includes Skiko natives for Windows, Linux and macOS on x64 and arm64. Bundling a native library does not establish runtime support for every OS/GPU/driver combination. Actual game GPU validation has covered Windows x64 on NVIDIA hardware. Hosted Windows/Linux CI covers core/desktop checks and adapter builds, not real GPU clients. macOS, other GPU vendors, shader-mod combinations, device-loss recovery and long-duration stress require separate validation.

## Loader and API differences

| Area | Forge 1.20.1 | NeoForge targets |
| --- | --- | --- |
| Adapter namespace | `dev.composemc.forge` / `Forge*` hosts | `dev.composemc.neoforge` / `NeoForge*` hosts |
| Dependency metadata | `mods.toml`, `mandatory=true` | `neoforge.mods.toml`, `type="required"` |
| Menu screen registration | Client setup `enqueueWork`, `MenuScreens.register` | `RegisterMenuScreensEvent` |
| Sync transport | `SimpleChannel` | Payload registration |
| Native codec bridge | `FriendlyByteBuf` / `PacketCodec` | Registry-aware `StreamCodec` bridge |
| Config spec | `ForgeConfigSpec`, legacy list/restart metadata | `ModConfigSpec`, target-specific modern metadata |

Shared Ore, snapshot, sync and slot-policy code has one source. Source compatibility of game-facing signatures still depends on Minecraft: tooltip extraction, input events and graphics APIs change across versions. Use the matching target's `dev` artifact instead of compiling against one target and assuming cross-version binary compatibility.

## Vulkan stencil-pipeline cleanup

The pinned NeoForge 26.2.0.88 Vulkan code creates an additional stencil pipeline and omits its destruction. Validation can report `VUID-vkDestroyDevice-device-05137` when the device shuts down; it also reproduces without a Compose renderer. The omitted release can retain resources before shutdown, so it should not be treated as only a harmless log message.

The reviewed 26.3 stencil path creates the extra pipeline conditionally but still omits its release. A tested scene reporting zero validation errors does not prove that every stencil-enabled path is unaffected. These statements describe the pinned/reviewed code, not an assertion about the latest upstream release.

Source references: [26.2 patch](https://github.com/NeoForged/NeoForge/blob/1ad7d233fc1ff8c3cb5c8b159f1701aabf4b7b96/patches/com/mojang/blaze3d/vulkan/VulkanRenderPipeline.java.patch) and [26.3 patch](https://github.com/NeoForged/NeoForge/blob/a323cff632de78d54fe82e48ee5e3fa1a82bf4e3/patches/com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline.java.patch). [Issue #3389](https://github.com/NeoForged/NeoForge/issues/3389) is a broader validation report, not a confirmed dedicated report for this omission.

Compose MC contains no project Mixin workaround. Prefer an upstream fix and revalidate a loader upgrade. The 26.2 validation task records its configured known diagnostic separately; that exception is not evidence that the defect is fixed.

## Input and integrations

The adapters support committed text, selection and clipboard editing. Full IME composition/candidate-window integration is not established. Platform font fallback also varies; see [Ore typography](ore-ui.md).

Inventory hosts preserve native container hooks and geometry, but third-party compatibility is not universal. Test recipe-viewer, shader and input integrations in your own target/modpack. GPU timings from bounded UI fixtures do not describe whole-game FPS or establish long-term leak freedom.
