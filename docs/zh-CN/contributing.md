# 参与开发

[English](../en/contributing.md) · [全部指南](../README.md)

本页介绍如何从源码构建 CompixelUI、在游戏中试用，以及检查改动。命令在仓库根目录运行。示例使用 Windows PowerShell；在 Linux 和 macOS 上用 `bash gradlew` 加相同参数。

## 准备

- Gradle 需要 JDK 17 或更高版本，推荐 JDK 25。各 Minecraft 版本还会用到 JDK 17、21 和 25。Gradle 会自动找到已安装的 JDK，缺少时自动下载；安装位置特殊时，设置 `COMPIXEL_JDK17`、`COMPIXEL_JDK21` 和 `COMPIXEL_JDK25`。
- 在 IntelliJ IDEA 中以 Gradle 项目打开仓库。

## 构建

```powershell
.\gradlew.bat buildAllMods
.\gradlew.bat '-PcompixelTargets=1.21.1,26.3' buildAllMods
.\gradlew.bat '-PcompixelTargets=none' checkCore
```

`-PcompixelTargets` 选择 Minecraft 版本：`all`（默认）、`none`（只构建共享代码），或用逗号分隔的版本列表。每个版本的产物位于 `minecraft/<加载器>-<版本>/build/`：

| 文件 | 内容 |
| --- | --- |
| `release/compixel-…-with-kotlin.jar`、`release/compixel-….jar` | 两个玩家文件 |
| `libs/compixel-….jar` | Maven 库，运行时需从 Maven 获取 |
| `libs/…-development.jar` | F8 组件预览模组 |
| `libs/…-sources.jar`、`libs/…-javadoc.jar` | 源码与 API 参考 |

要在自己的模组里试用本地构建，把它发布到 `build/consumer-maven`，再把这个目录添加为 Maven 仓库：

```powershell
.\gradlew.bat '-PcompixelTargets=1.21.1' :minecraft:neoforge-1.21.1:publishAllPublicationsToConsumerRepository
```

## 启动游戏

| Minecraft | Gradle 任务与参数 |
| --- | --- |
| 1.20.1 | `:minecraft:forge-1.20.1:runClient -PcompixelTargets=1.20.1` |
| 1.21.1 | `:minecraft:neoforge-1.21.1:runClient -PcompixelTargets=1.21.1` |
| 26.1.2 | `:minecraft:neoforge-26.1.2:runClient -PcompixelTargets=26.1.2` |
| 26.2 | `:minecraft:neoforge-26.2:runClient -PcompixelTargets=26.2` |
| 26.3 | `:minecraft:neoforge-26.3:runClient -PcompixelTargets=26.3` |

在 IDEA 中新建 Gradle 运行配置，把其中一行填入 **Tasks and arguments**。26.2 和 26.3 可以追加 `-PcompixelBackend=vulkan` 或 `-PcompixelBackend=opengl` 选择渲染后端。

在游戏中按 **F8** 打开组件预览，其中包含全部 Ore UI 控件：

![F8 组件预览](../assets/preview-zh-CN.png)

## 测试

- `checkCore` 检查模块边界和代码格式，并运行单元测试和离屏界面测试。
- `:desktop:run` 在桌面窗口中打开组件预览，`:desktop:smoke` 把它渲染到 `desktop/build/screenshots`。
- 两套客户端测试在真实游戏的全新测试世界中运行。`runAcceptance` 检查渲染、输入、物品、容器、菜单同步、HUD、窗口缩放、资源重载和资源释放；`runBenchmark` 测量帧耗时。

```powershell
.\gradlew.bat '-PcompixelTargets=1.21.1' :minecraft:neoforge-1.21.1:runAcceptance
.\gradlew.bat '-PcompixelTargets=26.3' :minecraft:neoforge-26.3:runBenchmark '-PcompixelBackend=vulkan'
```

测试报告（游戏目录中的 `compixel-acceptance.txt` 或 `compixel-benchmark.txt`）以 `PASS` 开头即为通过。在 Windows 上，`tools\run_background_acceptance.ps1` 和 `tools\run_background_benchmark.ps1` 会在独立的隐藏桌面上依次运行所有版本，游戏不会遮挡屏幕或抢占焦点。`python tools/summarize_benchmarks.py` 用于对比性能报告。

Forge 1.20.1 的玩家文件被重映射为 SRG 名称，因此由 `tools\run_forge_production.ps1` 在真实的 Forge 安装中测试。

## 提交之前

- 运行 `.\gradlew.bat spotlessApply` 格式化 Kotlin、Java 和 Gradle 文件。
- 修改依赖或模块后，运行 `verifyCoreBoundary`。
- 每个 Minecraft 版本都有自己的一份游戏相关代码。修复要应用到所有受影响的版本，并分别测试。
- 客户端测试驱动在所有版本中完全相同，由 `verifySuiteParity` 保持一致。
- 改动运行时包的内容（例如 Compose、Skiko 或 Kotlin 版本）时，提高 `gradle.properties` 中对应的 `runtime_*_version`，并运行 `:runtime-<名称>:writeBundleLock`。
- 修改文档后，运行 `node tools/check_docs.mjs`。
