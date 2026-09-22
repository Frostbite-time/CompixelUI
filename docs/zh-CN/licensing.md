# 许可与再分发

[English](../en/licensing.md) · [文档目录](../README.md)

Compose MC 自有源码和文档采用 [MIT 许可证](../../LICENSE)，版权署名为 Frostbite-Time。MIT 允许使用、修改和再分发，包括商业使用，但需保留版权和许可声明。项目 MIT 许可不替代所含第三方库、字体及游戏资源的许可。

## 发行内容

两种安装包均包含 Compose、AndroidX、Skiko、Swing 调度器集成、atomicfu 和 Monocraft。`with-kotlin` 另含 Kotlin 标准库、协程 Core 和 Serialization Core；标准 JAR 从外部提供者获取这些库。`dev` 产物包含完整 JVM 编译 API 及相应声明。可选 `development` 模组包含自身 MIT 声明和第三方署名。

KFF 的实现采用 LGPL-2.1，由用户单独安装；Compose MC 不复制或嵌入 KFF。Minecraft、Forge/NeoForge 和 LWJGL 由游戏安装环境提供。Gradle Wrapper 是构建工具，不属于游戏模组内容。

完整组件表和署名见[第三方声明](../../THIRD-PARTY-NOTICES.md)。大多数 JVM 依赖声明 Apache-2.0；Skia 原生组件另涉及 BSD、MIT/Old MIT、FTL、Unicode、IJG、libpng、zlib 和 Adobe DNG SDK 条款。FreeType 选择 FTL 分发；Monocraft 保留 OFL-1.1。展示图中的 Minecraft 资源不因项目 MIT 许可而获得额外的资源或商标授权。

## 产物中的许可文件

- `META-INF/composemc/LICENSE`：项目 MIT 正文。
- `META-INF/composemc-third-party/THIRD-PARTY-NOTICES.md` 和 `licenses/`：第三方署名、保留的原生及通用许可正文。
- 运行时包内（`dev` 中为展开形式）的 `META-INF/composemc-third-party/dependencies.json` 与 `dependencies.txt`：实际打包坐标及许可声明。每个坐标有对应目录，保存许可副本、适用的上游 POM，以及依赖 JAR 原有的 LICENSE/NOTICE。
- `dev/composemc/ui/ore/Monocraft-LICENSE.txt`：随字体保留的原始 OFL 声明。
- 源码与文档 JAR 保留项目 MIT 声明。`javadoc` JAR 另含 `third-party/`，保存 Dokka/前端资源声明；这些仅用于文档的资源不随 Minecraft 模组安装。

直接再分发 JAR 时保留这些内容。提取或重新打包依赖时，应保留对应组件所要求的声明，不能只保留项目 MIT 文件。原生 DNG SDK 遵循 Adobe 自有协议，其中含有超出普通 MIT 声明的条件。

## 升级依赖

运行时声明任务读取实际解析产物的 POM。目前打包的外部 JVM 产物均声明 Apache-2.0；若新增其他许可或缺少许可声明，任务会失败，直到补齐审核后的分发处理。这样不会仅凭组织名就假定未来所有依赖采用相同许可。

[原生来源记录](../../licenses/provenance.json)保存源文件引用和许可正文哈希。两份固定的 Skia 源码使用相同 DEPS 修订，但不同平台/后端的子集不同。Polyfrost 源码引用指向已检查的分支提交，并非发行标签。升级 Skiko 时应核对链接输入和原生声明、更新来源记录，再运行 `buildAllMods` 与 `node tools/check_docs.mjs`。这些检查验证打包元数据和覆盖情况，不构成所有法律问题的完整认证。
