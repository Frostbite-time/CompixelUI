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

公共渲染场景、CPU 参考像素和预览交互脚本集中在 `testing`。共享的原生物品视觉场景与像素断言也位于此处；所有适配器都在打包客户端中检查透明度、旋转、形状裁剪、遮挡和重复放置。后端探针分别位于 `render-gl/src/testFixtures` 和 `render-vulkan/src/testFixtures`；版本相关的设备准备和屏幕检查集中在各适配器的 `development/render` 包内。两套客户端测试开始时都会先运行所选 GPU 后端的探针，以两种视口尺寸重复验证绘制、重置、关闭和像素结果。Vulkan 验证层额外检查 API 与同步错误。所有测试辅助代码均不进入正式包和消费者 API 包。

`runClient` 包含开发预览，在游戏中按 **F8** 打开。26.2/26.3 可追加 `-PcomposemcBackend=opengl` 或 `-PcomposemcBackend=vulkan`，`auto` 跟随宿主后端。不支持 Vulkan 的目标会明确拒绝 Vulkan 请求。

## 构建产物

```powershell
.\gradlew.bat '-PcomposemcTargets=26.3' :minecraft:neoforge-26.3:build
```

玩家安装 `minecraft/<loader>-<mc>/build/release/` 中的 JAR；Maven 消费者使用 `build/libs/` 中的产物：

| 文件名 | 用途 |
| --- | --- |
| `release/composemc-<loader>-<mc>-0.1.0-alpha.35.jar` | 标准安装包，需要外部 Kotlin 提供者 |
| `release/…-with-kotlin.jar` | 自带 Kotlin 的另一种安装选择，只能选装一个版本 |
| `libs/composemc-<loader>-<mc>-0.1.0-alpha.35.jar` | 不含运行时的 Maven 库 JAR，不是可安装模组 |
| `libs/…-development.jar` | 可选 F8 预览/探针模组，与正式 JAR 同时安装 |
| `libs/…-sources.jar` | 库 JAR 的项目源码，不是可安装模组 |
| `libs/…-javadoc.jar` | Dokka HTML API 参考，不是可安装模组 |

1.20.1 的加载器名为 `forge`，其他目标为 `neoforge`。Forge 下 `build/release` 和 `build/libs` 中的 JAR 均已重混淆；`build/devlibs` 下的命名 JAR 用于开发启动。`build` 生成表中的全部六种产物。

发布 JAR 内嵌的运行时包由 `runtimes/` 单独构建，版本号各自独立，写在根目录 `gradle.properties` 的 `runtime_<name>_version` 中：
- `composemc-runtime-standard` 或 `composemc-runtime-vulkan`，装有 Compose 和 Skiko；
- `composemc-kotlin`，只嵌入 `with-kotlin` JAR。

Maven 库 JAR 含有相同的 Compose MC 类，并把对应的运行时包声明为依赖；它的 `-with-kotlin` 坐标只有一个 POM，额外加入 `composemc-kotlin`。`verifyReleaseIsolation` 检查每个发布 JAR 除内嵌运行时包外都与库 JAR 一致。每个运行时包的 `bundle.lock` 记录该版本包含的依赖。已发布的版本不能再改，所以依赖变化时要先提高版本号，再运行 `:runtime-<name>:writeBundleLock`。

`sourcesJar` 收集当前适配器的正式源码和随包分发的共享模块，Forge 1.20.1 也采用可读开发映射。它不包含其他适配器、开发探针或第三方源码。`javadocJar` 将 Dokka 2.2.0 生成的 HTML 打包，包含双语概览及同一套源码的公开声明。成员说明来自现有 KDoc/Javadoc 注释，中英文指南继续承担教程和用法说明。

例如可分别运行 `:minecraft:neoforge-1.21.1:sourcesJar` 或 `:minecraft:neoforge-1.21.1:javadocJar`。HTML 可从 `minecraft/neoforge-1.21.1/build/dokka/html/index.html` 打开。各适配器的文档生成串行执行以控制内存使用。`check` 包含 `verifyApiArchives`，核对源码与当前工作区一致、共享及适配器代表性页面存在、双语概览完整，并检查本机路径意外泄漏。

为消费者发布至本地仓库：

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:publishAllPublicationsToConsumerRepository
```

仓库位于 `build/consumer-maven`。该任务会一并发布 POM 所引用的运行时包，不会发布到外部 Maven 服务。消费者依赖见[快速开始](getting-started.md)。

每个 JAR 都带常规的 `sources` 和 `javadoc` 附件。运行时包的附件装的是其中各库自己发布的内容：源码合并为一棵目录树，各库的 API 文档各占一个目录。每个附件里有一份 README，列出包含的库以及上游未发布对应附件的库。若 IDE 未自动下载，请开启源码/文档下载并刷新 Gradle 项目。

## 发布正式版本

两个手动 Actions 工作流分别交付不同产物：[maven-publish](../../.github/workflows/maven-publish.yml) 将开发依赖发布到 `https://maven.wintercogs.com/releases`；[mod-publish](../../.github/workflows/mod-publish.yml) 将玩家安装包发布到 CurseForge 和 GitHub Releases。两个工作流都不会改变仓库可见性。

启动任一工作流前，在本机对所选提交完成 `checkCore buildAllMods`、受影响的隐藏客户端套件和真实消费者验证。GPU 验收与完整性能验证继续由本机承担；GitHub 运行机保留现行的分目标构建检查。验证结果应与该提交对应。

### Maven 开发依赖

`maven-publish` 任务使用同名环境。配置审批人、允许发布的分支/tag，再添加 `REPOSILITE_TOKEN_NAME` 和 `REPOSILITE_TOKEN_SECRET`，即对 `/releases` 有写权限的 Reposilite 访问令牌。仅在 YAML 中引用环境不会配置保护规则；私有仓库的环境与审批功能受 GitHub 套餐限制。

1. 提高 `gradle.properties` 中的 `mod_version` 并推送。
2. 对该提交启动 `maven-publish`。第一个任务不使用 secret，只检查仓库：适配器版本的 POM 已存在就直接失败，同时找出缺少的运行时版本。运行时检查要求主 JAR、POM、sources、javadoc 以及四份格式有效的 SHA-256 校验文件齐全，不再仅凭 POM 判断完成。
3. 如果有新的运行时版本，运行时任务先等待审批。每次执行都重新查询各包，构建检查和附件，在上传前再次查询，并在上传后确认文件齐全。已完整发布的运行时直接跳过，包括同一个失败任务前次已经上传成功的包。不会自动覆盖已发布版本。
4. 随后 5 个 Minecraft 目标的任务一起等待审批，批准一次即可全部放行。每个任务先构建并校验自己的适配器，再发布库和 `-with-kotlin` POM。

运行时只上传了部分文件时，检查会失败并列出缺失文件，包括 POM 已存在、只上传了 JAR 或遗留校验文件的情况。请在 Reposilite 检查并仅删除这个不完整版本，再重跑失败任务；保留已完整发布的运行时。检查核对文件存在性和校验文件格式，不会重新下载已发布内容进行哈希比对。适配器任务在部分上传后中断，仍需要先人工清理该适配器不完整的 library/with-kotlin 版本再重试。已有部分适配器成功时，应选择“重跑失败任务”，而非重新启动整个工作流，因为最初的适配器预检会主动拒绝已发布版本。

上传经过 Cloudflare，其单个请求的上限（较低档套餐为 100 MB）限制了运行时包的大小；目前最大的包约 75 MB。仓库名 `wintercogs` 对应 Gradle 的凭据属性 `wintercogsUsername` 和 `wintercogsPassword`，工作流从上述 secret 设置它们。

### 玩家安装包

配置 `mod-publish` 环境及所需审批/分支限制，在其中添加 secret `CURSEFORGE_TOKEN`。将 **仓库变量** `CURSEFORGE_ID` 设为项目的数字 ID。GitHub 发布使用具有 `contents: write` 权限的任务 `GITHUB_TOKEN`，无需个人 GitHub 令牌。

1. 对本机已验证的提交手动启动 `mod-publish`，填写更新说明。版本来自 `mod_version`，Release tag 为 `v<mod_version>`；已存在的轻量或附注 tag 必须指向所选提交。Alpha/beta 版本分别设置对应的 CurseForge 类型，并标记为 GitHub 预发布。
2. 从目标索引生成构建矩阵。每个目标只构建一次，收集 `build/release` 中的两个玩家安装包及 SHA-256 校验文件。所有构建成功后才启动两个平台的发布任务；发布时下载本轮构建产物并再次校验哈希。
3. 使用 `Kir-Antipov/mc-publish` 将两种变体作为 **独立 CurseForge 文件** 上传，分别标注准确的 Minecraft 版本、加载器和 Java 版本。标准版要求安装适配器 `curseforge_kotlin_provider` 指定的提供者，`with-kotlin` 不添加外部 Kotlin 依赖。26.3 的提供者显式留空，因为已验证的 KFF 6.3.0 排除了该版本；标准文件的说明会注明需要兼容的独立提供者。新增目标或更新已验证提供者时，应一并检查这个属性。
4. 单个 GitHub 发布任务创建/更新 Release，交付十个安装包、`SHA256SUMS` 和更新说明。玩家只能选装一种变体；不会混入 Maven 库、开发、源码或文档 JAR。

CurseForge 与 GitHub 使用独立任务，因此单个平台失败不必重跑已成功的平台。每个 CurseForge 任务只上传一个文件，并关闭自动上传重试；若上传响应丢失，应先检查项目文件列表再重试，避免重复文件。使用“重跑失败任务”复用已构建产物，不要盲目重跑十个 CurseForge 上传。上游 action 在重试时会替换 GitHub 中同名附件；tag 检查阻止用不同提交发布相同 tag。CurseForge 的审核以及对新 Minecraft/Java 版本的支持仍由平台决定，本机检查不代表平台已接受上传。

## 桌面预览与文档图片

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :desktop:smoke
.\gradlew.bat '-PcomposemcTargets=none' :desktop:run
```

`smoke` 将当前场景离屏渲染至 `desktop/build/screenshots`，包含中英文布局及交互状态，并检查空白渲染和嵌套提示边界。`run` 打开交互式桌面预览。桌面图片用于确认组件外观，不代表 Minecraft GPU 兼容性。

文档资源使用重新获取、未经编辑的渲染图。[图片来源](../assets/README.md)列出捕获后应复制的文件名。更新界面时，应同时更新图片说明和两种语言页面。

## 客户端测试套件

每个目标都在真实 Minecraft 客户端中运行两套自动测试：**acceptance** 检查正确性，**benchmark** 测量性能。两者都加载打包后的模组归档，并同时加载独立的开发包：NeoForge 目标加载的就是正式发布包；Forge 1.20.1 加载的是同一归档在 SRG 重映射之前的版本（见 [Forge 1.20.1 生产启动](#forge-1201-生产启动)）。两者开始前都会新建扁平创造模式测试世界 `saves/composemc-<suite>`（每次运行都会替换），渲染距离为 2，静音，关闭垂直同步。输入通过真实的 Screen 回调送达，并使用逻辑窗口焦点，因此两套测试都不会读取、依赖或抢占系统焦点，隐藏运行与可见运行执行完全相同的帧。测试不会接触系统剪贴板。

五个目标运行完全相同的测试。各适配器 `development` 包中的驱动代码（`SuiteEnvironment`、`SuiteSession`、`ClientAcceptanceProbe`、`PreviewAcceptance`、`ClientBenchmarkProbe` 和 `NativeTooltipProbe`）是逐字节一致的副本，版本差异只放在该适配器的 `SuitePlatform.kt` 和版本专属夹具中。`checkCore` 包含的 `verifySuiteParity` 会拒绝出现偏差的副本、缺失的夹具或错误的目标名。有序步骤表和基准测量规程位于 `testing`：只有全部步骤（或全部计划用例）按顺序完成后，报告才能写 `PASS`。

| 任务 | `minecraft/<adapter>/build/` 下的游戏目录 | 输出 |
| --- | --- | --- |
| `runAcceptance` | `acceptance-<backend>-<window>/` | `composemc-acceptance.txt`，截图位于 `acceptance-results/` |
| `runBenchmark` | `benchmark-<backend>-<window>-<label>/` | `composemc-benchmark.txt`，`benchmark-results/report.json`，每个用例一个 CSV 和 PNG |

`<window>` 为 `foreground` 或 `background`。报告不以 `PASS` 开头时任务失败；失败报告会写明所在步骤并附带堆栈。

### 使用 Gradle 可见运行

```powershell
.\gradlew.bat '-PcomposemcTargets=1.21.1' :minecraft:neoforge-1.21.1:runAcceptance
.\gradlew.bat '-PcomposemcTargets=26.3' :minecraft:neoforge-26.3:runBenchmark '-PcomposemcBackend=vulkan' '-PcomposemcLabel=candidate'
```

1.20.1 使用 `forge-1.20.1`。可见运行会显示游戏窗口，但仍使用逻辑焦点，其他窗口可以留在前台。可选属性：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `composemcBackend` | `auto` | `opengl`、`vulkan`（26.2/26.3）或 `cpu` |
| `composemcLabel` | `baseline` | 基准目录和报告标签 |
| `composemcBenchmarkFrames` | `360` | 每个用例的测量帧数，120–1500 |
| `composemcBenchmarkRepeats` | `2` | 重复次数，1–5，用例顺序正反交替 |
| `composemcBenchmarkControl` | `false` | 执行相同的启动、世界和资源重载，但不创建 Compose 渲染器 |
| `composemcKotlinMode` | `bundled` | `external` 安装不含 Kotlin 的正式包；追加 `composemcKotlinProviderJar=<jar>` 部署提供者，省略则检查缺少运行时的报错 |
| `composemcVulkanValidation` | `false` | 启用校验层，并拒绝意外的 `VUID-`/`SYNC-HAZARD` 消息 |

提供者只安装到测试游戏目录，绝不嵌入发布产物。`composemcBackground=true` 会在启动后隐藏窗口，但除非同时设置 `composemcIsolatedDesktop=true`，Gradle 会拒绝运行：否则窗口仍会先出现在用户桌面上。下面的 Windows 脚本会同时设置两者。`runClient` 仍是自带运行时的源码开发启动。

### 在 Windows 上隐藏运行

```powershell
.\tools\run_background_acceptance.ps1
.\tools\run_background_benchmark.ps1
.\tools\run_background_acceptance.ps1 -Minecraft 26.2,26.3 -Backend vulkan
.\tools\run_background_benchmark.ps1 -Minecraft 1.21.1 -Label candidate -Frames 600 -Repeats 3
```

两个脚本默认覆盖全部目标，逐个版本运行；每个版本都由 [run_isolated_gradle.ps1](../../tools/run_isolated_gradle.ps1) 在独立且从不激活的 Win32 桌面上启动新的 Gradle 进程。游戏不会出现在交互桌面上，不抢焦点，也不发出声音，且不发送任何系统键鼠输入。某个版本失败不会中断其他版本：脚本最后打印 PASS/FAIL 表，只要有版本失败就以错误退出。日志位于 `.work/suites/`。两个包装脚本都调用 [run_background_suite.ps1](../../tools/run_background_suite.ps1)，并传入 `-Suite acceptance` 或 `-Suite benchmark`；它还接受 `-KotlinMode external -KotlinProviderJar <jar>` 和 `-ValidationLayerPath <directory>`。使用 `-Backend vulkan` 时会跳过没有 Vulkan 的目标。

### 正确性覆盖

每个目标都按 [ClientSuites.kt](../../testing/src/main/kotlin/dev/composemc/testing/suite/ClientSuites.kt) 中的顺序通过以下步骤：

1. 库与开发包翻译，以及加载器的生产/开发模式。
2. 所选渲染后端在两种视口尺寸下、多次绘制/重置/关闭后与 CPU 参考像素一致。
3. 新建的测试世界。
4. 原生容器：左右键点击、逐格原生渲染钩子、服务端确认和界面释放。
5. 服务端打开的菜单同步：有界的多批次快照、分片动作往返，以及原生物品经同一个共享 codec 双向传递。
6. 配置编辑：暂存、标量与列表校验、保存和恢复。
7. 像素夹具：左上角指针坐标、预乘透明度、Unicode 文本和快捷键；随后是 GUI 缩放 3、帧缓冲调整尺寸和资源重载。
8. 原生物品视觉场景：透明度、旋转、形状裁剪、遮挡和重复放置。
9. 游戏画面上的 HUD 层：Compose 与原生物品像素、在接收输入的屏幕下方继续绘制、随 GUI 隐藏，同一会话经历 GUI 缩放、帧缓冲调整尺寸和资源重载；随后在玩家离开世界或调用 `close()` 时释放，下一帧开启新会话。
10. 通过按键映射打开的真实 F8 预览：保留帧、文本输入、弹窗 Escape 优先级、窗口尺寸与 GUI 缩放、10 万行列表命中测试与滚动、Compose 之后 Minecraft 自身绘制的原生物品像素、原生提示流程（延迟、替换、收纳袋富图像、取消、关闭、弹窗抑制、边缘定位、GUI 缩放和重载）、1 万行原生滚动、资源重载、逻辑焦点丢失与恢复、关闭、重新打开、全部 Ore 组件页面以及 12 次打开/关闭循环。

每次等待都有时限；每个被替换的 Compose 界面和关闭的 HUD 层都必须释放会话、表面和原生图像。

### 基准测量规程

每个目标都测量同一份 [BenchmarkPlan](../../testing/src/main/kotlin/dev/composemc/testing/suite/BenchmarkPlan.kt)：1280×960 帧缓冲、GUI 缩放 2、帧率上限 60、关闭垂直同步、2400 帧管线预热；每个用例再依次运行 120 帧预热、测量帧和 16 帧 GPU 排空。用例包括静态界面、动画、1k/10k/100k 行列表、静态与滚动的原生图标、同屏 256 个不同的动画原生图标、富提示、游戏画面上的静态与动画 HUD 层以及全部 Ore 组件页面。样本不完整、静态场景重绘、动画停止、图标饥饿或提示失去悬停时，用例会被判为无效而不是写入结果。

CPU 指标是屏幕渲染回调内的墙钟时间，HUD 用例则是 HUD 层内的墙钟时间；GPU 数值是按帧 ID 关联到原始帧的异步命令流区间，不使用阻塞读取。不要相加 CPU/GPU 时间、将缺失样本记为零，或将结果当作整局游戏 FPS。在测试之外，`-Dcomposemc.profile=true` 开启帧记录，`-Dcomposemc.allocations=true` 增加受支持的 JVM 分配测量；在客户端线程读取 `frameProfiler`。

使用汇总脚本比较同一台机器、同一规程的报告。只传一个报告时输出其用例表；传入多个时每个报告一列，并给出相对第一个报告的平均变化，可用于版本间对比或修改前后对比：

```powershell
python tools/summarize_benchmarks.py minecraft/neoforge-1.21.1/build/benchmark-opengl-background-baseline/benchmark-results/report.json minecraft/neoforge-26.3/build/benchmark-opengl-background-baseline/benchmark-results/report.json
```

[26.2 上游已知诊断](compatibility.md)与意外校验错误分开处理。

### Forge 1.20.1 生产启动

只有 Forge 1.20.1 的正式包会为生产环境重映射（SRG），因此它的 Gradle 运行无法加载玩家实际安装的文件。该脚本安装 Forge，并针对这个正式包运行任一套测试；NeoForge 目标的 Gradle 运行本身就加载其正式包：

```powershell
.\tools\run_forge_production.ps1 -JavaHome C:\path\to\jdk17 -Suite acceptance
.\tools\run_forge_production.ps1 -JavaHome C:\path\to\jdk17 -Suite benchmark -Label production
```

独立同步诊断可运行：

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:check :slot-core:check
.\gradlew.bat '-PcomposemcTargets=none' :menu-sync:profileSyncCollection :menu-sync:profileSyncMap
```

性能结果位于 `menu-sync/build/profiles`，只测量 JVM 快照/协议工作，不包含 Minecraft、网络延迟或渲染。耗时用于诊断，不作为通过/失败门槛。

## CI 与修改检查

[CI](../../.github/workflows/verify.yml)在 Windows/Linux 检查共享核心，在 Linux 构建全部五个适配器。真实 GPU 验证在合适的宿主上单独执行。

代码格式由 Spotless 统一：Kotlin 源码与 Kotlin 构建脚本使用 ktfmt（Kotlin 编码约定风格，行宽 120），Java 使用 palantir-java-format，Groovy 构建脚本只检查空白。提交前运行 `.\gradlew.bat spotlessApply`。CI 使用的 `checkCore` 会运行 `spotlessCheck`；格式化整个仓库较慢，因此 `check` 和 `build` 不运行它。`.editorconfig` 为编辑器提供相同的缩进与行宽；安装 ktfmt 和 palantir-java-format 的 IntelliJ 插件可得到与格式化器完全一致的结果。`.git-blame-ignore-revs` 列出只改格式的提交；运行 `git config blame.ignoreRevsFile .git-blame-ignore-revs` 可让本地 blame 跳过它们。

修改依赖或模块边界后，运行 `verifyCoreBoundary` 和受影响的测试/构建。修改共享公开 API 时，编译所有受影响适配器及独立消费者。渲染或原生生命周期变化还需要为对应版本/后端运行两套客户端测试。修改测试驱动时必须同时修改全部五份副本，否则 `verifySuiteParity` 会失败。

修改文档后运行 `node tools/check_docs.mjs`，检查本地链接、锚点、双语配对和图片引用。生成的 `build/`、`.gradle/` 和 `.work/` 可清理；应保留源码、Gradle Wrapper、目标元数据和依赖锁文件。
