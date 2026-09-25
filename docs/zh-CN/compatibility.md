# 兼容性

[English](../en/compatibility.md) · [文档目录](../README.md)

本页描述 **0.1.0-alpha.34** 的构建基线。[minecraft](../../minecraft) 下各适配器的 `build.gradle` 和 `gradle.properties` 管理自身构建配置，[目标索引](../../gradle/minecraft-targets.properties)仅用于选择目录，[版本目录](../../gradle/libs.versions.toml)管理共享依赖。采用发生 API 变化的 Alpha 版本时，应重新构建消费者。

## 版本目标

| Minecraft | 加载器 | 适配器 Java | 运行时 | 图形后端 |
| --- | --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.23 | 17 | Skiko 0.150.1 | OpenGL |
| 1.21.1 | NeoForge 21.1.250 | 21 | Skiko 0.150.1 | OpenGL |
| 26.1.2 | NeoForge 26.1.2.109 | 25 | Skiko 0.150.1 | OpenGL |
| 26.2 | NeoForge 26.2.0.88 | 25 | Polyfrost Skiko 0.999.6 | OpenGL / Vulkan |
| 26.3 | NeoForge 26.3.0.6-beta | 25 | Polyfrost Skiko 0.999.6 | OpenGL / Vulkan |

共享模块以 Java 17 为目标。每个适配器针对自己的 Minecraft/加载器 API 编译，不能将一个目标的 mod JAR 安装到另一个目标。消费者 Maven 坐标为 `dev.composemc:composemc-<loader>-<minecraft>:<version>`。

Kotlin 与 Compose 编译器为 2.4.10，Compose 为 1.12.0。每个目标提供具有相同 Mod ID 和 API 的两种安装包。不要混合 standard 与 Vulkan 图形配置，也不要将 Compose MC 内嵌进消费者。

## Kotlin 运行时提供者

| 安装包 | Kotlin 库 | 外部提供者 |
| --- | --- | --- |
| 标准 `composemc-…-0.1.0-alpha.34.jar` | 不包含 | 兼容的标准库、协程 Core 和 Serialization Core；推荐 KFF |
| `composemc-…-0.1.0-alpha.34-with-kotlin.jar` | 自带 | 不要与 KFF 或另一套 Kotlin 运行时混装 |

只能选装一个。两者均包含 Compose、Skiko、atomicfu 和 Swing 调度器集成，均不嵌入 KFF。标准 JAR 检查客户端运行时，不强制要求 `kotlinforforge` Mod ID。标准库至少为 2.2.21，协程及 Serialization 必须提供兼容 API。类和代表性方法检查不能保证所有第三方组合均兼容。

提供者基线：Forge 1.20.1 使用 KFF 4.12.0，NeoForge 1.21.1 使用 KFF 5.12.0，26.1.2/26.2 使用 KFF 6.3.0。它们提供协程 1.10.2 或 1.11.0 及匹配的 Serialization 库。KFF 6.3.0 排除了 26.3，该版本可使用 `with-kotlin` 或兼容的独立提供者。26.3 同样生成两种产物，没有特殊打包逻辑，无需仅为识别未来提供者而重发 Compose MC。

`dev` JAR 为两种安装方式提供完整的编译 API。本库纯 Java 的服务器菜单/槽位入口不依赖或初始化 Kotlin/UI；消费者可能有额外要求。

## 升级已有消费者

Ore UI 现按职责划分子包。请依据[组件与包对照表](ore-ui.md#选择组件)替换根包导入，重新构建消费者并安装匹配的库。该调整同时改变源码导入和 JVM 名称，已经编译的消费者也需要重新编译。

Alpha.33 使用菜单协议 **5**。请针对匹配的 `dev` 产物重新构建消费者，并同时升级客户端和服务端安装的库。协议 4 对端及旧的状态记录帧格式均不兼容。

| 接入位置 | 当前 API |
| --- | --- |
| `MenuSync.bind` 配置 | 传入 `MenuSyncOptions`，通过 `.withState(SyncLimits)` 和 `.withActions(ActionLimits)` 覆盖预算。 |
| `SyncLimits` 传输设置 | 第二项传入 `TransferBudget(refill, capacity, peak)`；`TransferBudget.steady(n)` 表示不积攒突发额度的固定每 tick 预算。 |
| `MenuSync.request` 返回值 | 处理 `ActionSubmission.queued()`、`failure()`、`actual()` 和 `limit()`；通过 `onActionResult` 接收最终执行结果。 |
| 动作大小常量 | `MenuAction.DEFAULT_MAX_BYTES` 表示 8 KiB 默认值。显式声明所需 `maximumBytes`，不再提供 `MAX_FRAGMENTED_BYTES` 常量。 |
| 自定义核心传输 | 调用 `SyncBatch.read(input, maximumBatchBytes)` 时传入传输上限；状态字段索引使用 32 位帧格式。 |

两端的状态/动作策略和动作声明必须一致。客户端确认菜单挂接与策略后才开始发送状态正文，打开时增加一次确认往返。完整默认值、拒绝处理及突发行为见[菜单同步](menu-sync.md)。

## 渲染

26.2/26.3 默认跟随 Minecraft 当前选择的后端，较早目标使用 OpenGL。原生 GPU 路径不经过整帧像素读回、PNG 转换和 CPU 上传。OpenGL 支持原版 1.20.1 使用的 3.2 上下文，可选查询和状态处理依据宿主能力启用。

`composemc.backend` 在所有目标上都接受 `auto`（默认）、`opengl` 和 `cpu`，26.2/26.3 另外接受 `vulkan`；其他目标会对 `vulkan` 明确报错。`-Dcomposemc.backend=cpu` 选择诊断参考渲染器，适合调查像素问题，不代表正常 GPU 性能。开发启动时的后端选择见[构建与测试](build-and-test.md)。

每个正式 JAR 均包含 Windows、Linux、macOS 的 x64 和 arm64 Skiko 原生库。打包原生库不等于已验证全部系统/GPU/驱动组合。实际游戏 GPU 验证覆盖 Windows x64 与 NVIDIA 硬件。托管 Windows/Linux CI 覆盖核心、桌面检查及适配器构建，不运行真实 GPU 客户端。macOS、其他 GPU 厂商、光影模组组合、设备丢失恢复和长时间压力仍需单独验证。

## 加载器与 API 差异

| 范围 | Forge 1.20.1 | NeoForge 目标 |
| --- | --- | --- |
| 适配器命名空间 | `dev.composemc.forge` / `Forge*` 宿主 | `dev.composemc.neoforge` / `NeoForge*` 宿主 |
| 依赖元数据 | `mods.toml`，`mandatory=true` | `neoforge.mods.toml`，`type="required"` |
| 菜单屏幕注册 | 客户端初始化的 `enqueueWork`、`MenuScreens.register` | `RegisterMenuScreensEvent` |
| 同步传输 | `SimpleChannel` | Payload 注册 |
| 原生 codec 桥接 | `FriendlyByteBuf` / `PacketCodec` | 支持注册表的 `StreamCodec` 桥接 |
| 配置规范 | `ForgeConfigSpec`，旧版列表/重启元数据 | `ModConfigSpec`，各目标的现代元数据 |

Ore、快照、同步和槽位策略共享同一份源码。游戏侧签名是否源码兼容仍取决于 Minecraft：提示提取、输入事件和图形 API 会随版本变化。应使用对应目标的 `dev` 产物，不能针对一个目标编译后假定二进制跨版本兼容。

## Vulkan 模板管线清理

当前固定的 NeoForge 26.2.0.88 Vulkan 代码创建额外模板管线后，遗漏了销毁。设备退出时可能报告 `VUID-vkDestroyDevice-device-05137`，没有 Compose 渲染器时也可复现。遗漏释放可能在退出前持续占用资源，因此不应仅视为无害日志。

已审查的 26.3 模板路径改为条件创建额外管线，但仍遗漏释放。某个测试场景没有校验错误，不代表所有启用模板的路径均不受影响。这里描述固定/已审查代码，不宣称上游最新版本仍然如此。

源码参考：[26.2 补丁](https://github.com/NeoForged/NeoForge/blob/1ad7d233fc1ff8c3cb5c8b159f1701aabf4b7b96/patches/com/mojang/blaze3d/vulkan/VulkanRenderPipeline.java.patch)、[26.3 补丁](https://github.com/NeoForged/NeoForge/blob/a323cff632de78d54fe82e48ee5e3fa1a82bf4e3/patches/com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline.java.patch)。[Issue #3389](https://github.com/NeoForged/NeoForge/issues/3389) 是更广泛的校验报告，并非已确认专门报告这一遗漏的 issue。

Compose MC 不包含针对该问题的项目 Mixin 修复。应优先采用上游修复，并在升级加载器后重新验证。26.2 校验任务会单独记录配置中的已知诊断，该例外不代表缺陷已解决。

## 输入与集成

适配器支持已提交文本、选区和剪贴板编辑，尚未建立完整 IME 组合态/候选窗集成的验证结论。平台字体回退也会不同，见 [Ore 字体说明](ore-ui.md)。

容器宿主保留原生钩子和几何位置，但不代表与所有第三方模组兼容。在所有目标上，屏幕渲染前钩子和背景钩子绘制在 Compose 层下方，前景钩子和渲染后钩子绘制在其上方。通过屏幕初始化事件添加的控件在容器宿主中显示在 Compose 层上方，普通 Compose 屏幕暂不支持这类控件。应在自己的目标版本和整合包中验证配方查看器、光影及输入集成。有界 UI 场景的 GPU 耗时不能代表整局游戏 FPS，也不能证明长期无泄漏。
