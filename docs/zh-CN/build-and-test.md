# 构建与测试

[English](../en/build-and-test.md) · [文档目录](../README.md)

在仓库根目录运行命令。Windows 示例使用 PowerShell 和 `gradlew.bat`；Linux/macOS 使用 `bash gradlew`，任务与属性相同。下面的游戏后台启动脚本仅适用于 Windows。

## Java 与目标选择

Gradle 需要 JDK 17 或更新版本，推荐 JDK 25。各适配器所需 Java 版本见[兼容性表](compatibility.md)。Gradle 会发现本机工具链，并可通过 Foojay 获取缺少的版本。非标准安装位置可通过 `COMPOSEMC_JDK17`、`COMPOSEMC_JDK21` 和 `COMPOSEMC_JDK25` 指定对应 JDK 目录。

默认使用 `-PcomposemcTargets=all`。也可选择单个目标、逗号分隔的多个目标，或用 `none` 仅配置共享模块：

```powershell
.\gradlew.bat '-PcomposemcTargets=none' checkCore
.\gradlew.bat '-PcomposemcTargets=1.21.1,26.3' buildAllMods
.\gradlew.bat buildAllMods
```

`checkCore` 检查模块边界、共享单元测试和桌面离屏场景。`buildAllMods` 构建并验证所有已启用适配器及 API 产物。编译成功不能替代真实客户端验收。

## IntelliJ IDEA

1. 将仓库根目录作为 Gradle 工程打开并刷新模型。在 **设置 → 构建工具 → Gradle → Gradle JVM** 选择已安装 JDK；适配器编译工具链单独配置。
2. 打开 **运行 → 编辑配置 → + → Gradle**，将根工程设为 Gradle 项目。
3. 在 **任务和参数（Tasks and arguments）** 填入下表之一，然后运行该配置。

| Minecraft | 任务和参数 |
| --- | --- |
| 1.20.1 | `:minecraft:forge-1.20.1:runClient -PcomposemcTargets=1.20.1` |
| 1.21.1 | `:minecraft:neoforge-1.21.1:runClient -PcomposemcTargets=1.21.1` |
| 26.1.2 | `:minecraft:neoforge-26.1.2:runClient -PcomposemcTargets=26.1.2` |
| 26.2 | `:minecraft:neoforge-26.2:runClient -PcomposemcTargets=26.2` |
| 26.3 | `:minecraft:neoforge-26.3:runClient -PcomposemcTargets=26.3` |

如果 Gradle 工具窗口没有任务树，在 IDEA 的 Gradle 设置中启用任务列表构建并重新加载。不同 IDEA 版本的选项名称可能不同；无需展开任务树也可以创建上述运行配置。被 `composemcTargets` 排除的目标，需要启用后重新导入才会出现在项目中。

适配器的 `development` 编译通过 Kotlin 的 `associateWith` 关联 `main`，允许预览和探针访问适配器的 `internal` 声明。构建约定变更后请重新加载 Gradle 项目，让 IDEA 导入该可见性关系。仅传入编译器 friend paths 并不能向 IDE 声明这种源码集关联。

渲染器探针归属 `render-gl/src/testFixtures`，可访问 `render-gl/main` 的内部声明。适配器通过普通依赖调用测试入口，不跨项目访问渲染器内部类。新增探针应放在其所检查内部行为的所属模块中，并且只进入开发产物。

`runClient` 包含开发预览，在游戏中按 **F8** 打开。26.2/26.3 可追加 `-PcomposemcBackend=opengl` 或 `-PcomposemcBackend=vulkan`，`auto` 跟随宿主后端。不支持 Vulkan 的目标会明确拒绝 Vulkan 请求。

## 构建产物

```powershell
.\gradlew.bat '-PcomposemcTargets=26.3' :minecraft:neoforge-26.3:build
```

在 `minecraft/<loader>-<mc>/build/libs/` 获取：

| 文件名 | 用途 |
| --- | --- |
| `composemc-<loader>-<mc>-0.1.0-alpha.34.jar` | 标准安装包，需要外部 Kotlin 提供者 |
| `…-with-kotlin.jar` | 自带 Kotlin 的另一种安装选择，只能选装一个版本 |
| `…-dev.jar` | 仅消费者编译类路径 |
| `…-development.jar` | 可选 F8 预览/探针模组，与正式 JAR 同时安装 |
| `…-sources.jar` | 标准版、自带版与 `dev` 共用的项目源码，不是可安装模组 |
| `…-javadoc.jar` | 共用的 Dokka HTML API 参考，不是可安装模组 |

1.20.1 的加载器名为 `forge`，其他目标为 `neoforge`。Forge 的两种安装包均已重混淆；`build/devlibs` 下的命名 JAR 用于开发启动。`build` 生成表中的全部六种产物。`runtimes/` 下是内部装配产物，不要作为第二个模组安装。

`sourcesJar` 收集当前适配器的正式源码和随包分发的共享模块，Forge 1.20.1 也采用可读开发映射。它不包含其他适配器、开发探针或第三方源码。`javadocJar` 将 Dokka 2.2.0 生成的 HTML 打包，包含双语概览及同一套源码的公开声明。成员说明来自现有 KDoc/Javadoc 注释，中英文指南继续承担教程和用法说明。

例如可分别运行 `:minecraft:neoforge-1.21.1:sourcesJar` 或 `:minecraft:neoforge-1.21.1:javadocJar`。HTML 可从 `minecraft/neoforge-1.21.1/build/dokka/html/index.html` 打开。各适配器的文档生成串行执行以控制内存使用。`check` 包含 `verifyApiArchives`，核对源码与当前工作区一致、共享及适配器代表性页面存在、双语概览完整，并检查本机路径意外泄漏。

为消费者发布至本地仓库：

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:publishLibraryPublicationToConsumerRepository
```

仓库位于 `build/consumer-maven`，该任务不会发布到外部 Maven 服务。消费者依赖见[快速开始](getting-started.md)。

附件使用常规 `sources`、`javadoc` classifier。`:dev` 消费者可通过 Gradle 标准 JVM 产物查询解析两者，生成的 IDEA 模型会将其关联到 dev 二进制。不另发重复的 `dev-sources` 或 `dev-javadoc`。若 IDE 未自动下载，请开启源码/文档下载并刷新 Gradle 项目。

## 桌面预览与文档图片

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :desktop:smoke
.\gradlew.bat '-PcomposemcTargets=none' :desktop:run
```

`smoke` 将当前场景离屏渲染至 `desktop/build/screenshots`，包含中英文布局及交互状态，并检查空白渲染和嵌套提示边界。`run` 打开交互式桌面预览。桌面图片用于确认组件外观，不代表 Minecraft GPU 兼容性。

文档资源使用重新获取、未经编辑的渲染图。[图片来源](../assets/README.md)列出捕获后应复制的文件名。更新界面时，应同时更新图片说明和两种语言页面。

## 打包客户端验证

打包基准默认自带 Kotlin。验证外部提供者时，在任一 Windows 基准脚本后追加 `-KotlinMode external -KotlinProviderJar C:\path\to\kotlinforforge.jar`。Gradle 打包运行任务对应参数为 `-PcomposemcKotlinMode=external -PcomposemcKotlinProviderJar=C:/path/to/kotlinforforge.jar`。提供者只安装到测试游戏目录，绝不嵌入发布产物。外部模式下省略提供者可检查缺失运行时的报错。`runClient` 仍是自带运行时的源码开发启动。

直接运行 `runClient`、`runClientSmoke`、`runPackagedSmoke` 和 `runBenchmark` 时，游戏窗口默认可见。smoke 任务可能操作真实剪贴板，适合有意进行的交互测试。单元测试和 `:desktop:smoke` 仍然不打开窗口。后台脚本会显式开启 `composemcBenchmarkBackground=true` 和桌面隔离；仅设置后台标志并不会自动隐藏启动过程。

自动游戏测试使用 Windows 隐藏启动器。它创建独立且从不激活的桌面，不发送系统键鼠输入，也不修改剪贴板：

```powershell
.\tools\run_background_benchmark.ps1 -Minecraft 1.21.1 -Backend opengl -Label validation -Frames 120
.\tools\run_background_benchmark.ps1 -Minecraft 26.3 -Backend vulkan -Label validation -Frames 120
```

启用 Vulkan 校验时，追加 `-ValidationLayerPath C:\path\to\validation-layer`。Forge 还提供针对可安装 SRG 映射 JAR 的生产启动检查：

```powershell
.\tools\run_forge_production_benchmark.ps1 -JavaHome C:\path\to\jdk17 -Label validation
```

基准测试将构建好的正式库/开发 JAR 安装到隔离游戏目录。`composemc-benchmark.txt`、日志和截图位于 `minecraft/<adapter>/build/benchmark-<backend>-background-<label>/`。1.21.1 的 PNG/CSV/JSON 写入 `benchmark-results/`，其他适配器的报告格式和场景数量不同。运行必须以 `PASS` 结束。[26.2 上游已知诊断](compatibility.md)与意外校验错误分开处理。

1.21.1 测量器针对屏幕渲染回调，包含预热和正反顺序重复。CPU 指标是墙钟耗时；GPU 时间戳是关联原始帧的异步区间。不要相加 CPU/GPU 时间、将缺失样本记为零，或将结果当作整局游戏 FPS。`-Dcomposemc.profile=true` 开启帧记录，`-Dcomposemc.allocations=true` 增加受支持的 JVM 分配测量。在客户端线程读取 `frameProfiler`。

独立同步诊断可运行：

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:check :slot-core:check
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:profileSyncCollection :menu-sync:profileSyncMap
```

性能结果位于 `menu-sync/build/profiles`，只测量 JVM 快照/协议工作，不包含 Minecraft、网络延迟或渲染。耗时用于诊断，不作为通过/失败门槛。

## CI 与修改检查

[CI](../../.github/workflows/verify.yml)在 Windows/Linux 检查共享核心，在 Linux 构建全部五个适配器。真实 GPU 验证在合适的宿主上单独执行。

修改依赖或模块边界后，运行 `verifyCoreBoundary` 和受影响的测试/构建。修改共享公开 API 时，编译所有受影响适配器及独立消费者。渲染或原生生命周期变化还需要验证对应版本/后端的打包客户端。

修改文档后运行 `node tools/check_docs.mjs`，检查本地链接、锚点、双语配对和图片引用。生成的 `build/`、`.gradle/` 和 `.work/` 可清理；应保留源码、Gradle Wrapper、目标元数据和依赖锁文件。
