# Third-party notices / 第三方许可声明

Compose MC's original code and documentation are licensed under MIT. Third-party code, fonts and game assets retain their own licenses; the project's MIT license does not replace them. Upstream license texts below are preserved in their original language.

Compose MC 自有代码和文档采用 MIT。第三方代码、字体及游戏资源保留各自许可，不因本项目采用 MIT 而改变。下列上游许可正文保留原文，不作翻译改写。

## JVM libraries / JVM 库

| Components / 组件 | License / 许可 | Distribution / 分发 |
| --- | --- | --- |
| AndroidX: annotation, arch.core, collection, Compose runtime, lifecycle, navigationevent, savedstate | Apache-2.0 | Both library variants / 两种库版本 |
| JetBrains Compose Multiplatform and AndroidX ports | Apache-2.0 | Both variants / 两种版本 |
| Kotlin standard library | Apache-2.0 | `with-kotlin` and compile-only `dev`; external in standard / 自带版及编译包，标准版使用外部提供者 |
| Kotlinx Coroutines Core and Serialization Core | Apache-2.0 | `with-kotlin` and `dev`; external in standard / 自带版及编译包，标准版使用外部提供者 |
| Kotlinx Coroutines Swing and atomicfu | Apache-2.0 | Both variants / 两种版本 |
| JetBrains annotations and JBR API interface library | Apache-2.0 | Both variants; a complete JBR/JDK is not included / 两种版本，不包含完整 JBR/JDK |
| JSpecify annotations | Apache-2.0 | Both variants / 两种版本 |
| Skiko (JetBrains standard and Polyfrost Vulkan distributions) | Apache-2.0 | Both variants; native components have additional licenses below / 两种版本，原生组件另见下表 |

The [Apache-2.0 text](licenses/APACHE-2.0.txt) accompanies these libraries. Original LICENSE/NOTICE files from resolved JARs are retained under component-specific directories. The generated `META-INF/composemc-third-party/dependencies.json` records each bundled coordinate, its declared license and license URL, with supplemental notices for Skiko's native components and Monocraft; `dependencies.txt` is the corresponding coordinate list. These inventories describe the actual library/runtime variant, not every dependency used by the build.

上述库附带 [Apache-2.0 正文](licenses/APACHE-2.0.txt)。依赖 JAR 原有的 LICENSE/NOTICE 按组件分目录保留。生成的 `META-INF/composemc-third-party/dependencies.json` 记录每个实际打包坐标及其声明的许可证、许可 URL，并关联 Skiko 原生组件和 Monocraft 的补充声明；`dependencies.txt` 为对应坐标列表。清单针对实际库/运行时版本，不等同于所有构建依赖。

Skiko's [upstream NOTICE](licenses/skiko-NOTICE.txt) acknowledges code adapted from the Android Open Source Project. Google, JetBrains, the Polyfrost contributors and the respective upstream contributors retain their applicable copyrights.

Skiko 的[上游 NOTICE](licenses/skiko-NOTICE.txt)说明包含改编自 Android Open Source Project 的代码。Google、JetBrains、Polyfrost 贡献者及各上游贡献者保留其相应版权。

## Native graphics libraries / 原生图形库

Native notices are based on the Skiko linker configuration and the pinned Skia source dependencies. Platform/backend subsets differ; the notices cover the native components across the universal distribution. [Provenance](licenses/provenance.json) records the reviewed source locations and SHA-256 of each retained text. Updating the native distribution requires reviewing these notices again.

原生声明依据 Skiko 链接配置和固定的 Skia 源码依赖整理。不同平台/后端使用的子集不同，下表覆盖通用发行包中的原生组件。[来源记录](licenses/provenance.json)保存已核对的源码位置和各许可正文的 SHA-256；升级原生发行版时需要重新核对。

| Component / 组件 | License text / 许可正文 |
| --- | --- |
| Skia — Google and contributors / Google 及贡献者 | [BSD-3-Clause](licenses/skia/LICENSE.txt) |
| FreeType | [FreeType License (FTL)](licenses/skia/freetype-docs-FTL.TXT); distributed using the FTL option / 选择 FTL 分发 |
| HarfBuzz | [Old MIT notices / Old MIT 声明](licenses/skia/harfbuzz-COPYING), [Microsoft shaping data / Microsoft 字形数据](licenses/skia/harfbuzz-ms-use-COPYING) |
| ICU and Unicode data / ICU 与 Unicode 数据 | [Unicode License V3 and additional notices / Unicode V3 及附加声明](licenses/skia/icu-LICENSE) |
| libjpeg-turbo / Independent JPEG Group | [License overview and BSD text / 许可总览与 BSD 正文](licenses/skia/libjpeg-turbo-LICENSE.txt), [IJG](licenses/skia/libjpeg-turbo-README.ijg) |
| libpng | [libpng licenses / libpng 许可](licenses/skia/libpng-LICENSE) |
| libwebp | [BSD](licenses/skia/libwebp-COPYING), [patent grant / 专利授权](licenses/skia/libwebp-PATENTS), [authors / 作者](licenses/skia/libwebp-AUTHORS) |
| zlib | [zlib license / zlib 许可](licenses/skia/zlib-LICENSE) |
| Expat | [MIT](licenses/skia/expat-expat-COPYING) |
| Brotli | [MIT](licenses/skia/brotli-LICENSE) |
| D3D12 Memory Allocator | [MIT](licenses/skia/d3d12allocator-LICENSE.txt) |
| Vulkan Memory Allocator | [MIT](licenses/skia/vulkanmemoryallocator-LICENSE.txt) |
| Vulkan Headers | [Apache-2.0 / MIT declaration / 许可声明](licenses/skia/vulkan-headers-LICENSE.txt), [MIT text / MIT 正文](licenses/skia/vulkan-headers-MIT.txt) |
| SPIR-V Headers | [MIT and per-file notices / MIT 及文件级声明](licenses/skia/spirv-headers-LICENSE) |
| SPIR-V Tools | [Apache-2.0](licenses/skia/spirv-tools-LICENSE) |
| SPIRV-Cross | [Apache-2.0](licenses/skia/spirv-cross-LICENSE) |
| Piex | [Apache-2.0](licenses/skia/piex-LICENSE) |
| Adobe DNG SDK (macOS) | [Adobe DNG SDK License Agreement](licenses/skia/dng_sdk-LICENSE) |

Required acknowledgments / 必要署名：

> This software is based in part on the work of the Independent JPEG Group.
>
> Portions of this software are copyright © 2026 The FreeType Project (https://freetype.org). All rights reserved.
>
> This product includes DNG technology under license by Adobe Systems Incorporated.

本软件部分基于 Independent JPEG Group 的工作，并包含 FreeType 代码。相应原生平台包含根据 Adobe Systems Incorporated 许可提供的 DNG 技术。上述英文署名按上游要求保留。

## Font, external runtime and build tools / 字体、外部运行时与构建工具

API documentation JARs additionally contain Dokka's static HTML assets. Their [separate notices](docs/api-assets-licenses/README.md) travel with the documentation; these assets are not included in installed mod JARs.

API 文档 JAR 另含 Dokka 静态页面资源，其[独立声明](docs/api-assets-licenses/README.md)随文档分发，不进入游戏安装包。

- **Monocraft**, copyright © 2022 Idrees Hassan, uses **SIL Open Font License 1.1**. The unmodified font and complete license remain together at `dev/composemc/ui/ore/Monocraft.ttf` and `Monocraft-LICENSE.txt` in the library runtime. The repository copy is under `ui-ore/src/main/resources/`. 字体未经修改，完整 OFL 正文始终随字体打包；它不受项目 MIT 许可替代。
- **Kotlin for Forge** uses **LGPL-2.1** for its own implementation. It is installed separately, never embedded or copied into Compose MC. Its bundled Kotlin libraries retain their own upstream licenses. KFF 独立安装，其实现采用 LGPL-2.1；Compose MC 不内嵌或复制 KFF，Kotlin 库仍保留各自上游许可。[Upstream license / 上游许可](https://github.com/thedarkcolour/KotlinForForge/blob/6.x/LICENSE)
- **Minecraft, Forge/NeoForge and LWJGL** are supplied by the game/loader installation. Their binaries are not bundled in Compose MC. Screenshots showing Minecraft-native items contain game assets whose rights remain with their owners. Minecraft、加载器和 LWJGL 由游戏环境提供；展示图中的原生物品等游戏资源保留原权利人的权利。本项目不代表 Mojang 或 Microsoft，也不授予其商标或资源的额外使用权。
- **Gradle Wrapper** is included for building the source checkout and retains its embedded Apache-2.0 license. It is not part of the installed mod. Gradle Wrapper 属于源码仓库的构建工具，保留 JAR 内的 Apache-2.0 许可，不进入游戏模组。

The MIT license of Compose MC covers its original work only. Distributors must retain the applicable third-party notices when redistributing bundled components. Native DNG SDK terms are Adobe's own agreement, not MIT. Supporting a library through an external runtime does not transfer that library's copyright to this project.

Compose MC 的 MIT 许可只覆盖自身原创内容。再分发所含第三方组件时，应保留对应声明。DNG SDK 遵循 Adobe 自有协议，并非 MIT；使用外部运行时也不改变该库的版权归属。
