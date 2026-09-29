# Third-party notices / 第三方许可声明

CompixelUI uses the following third-party libraries and fonts. Their uses and applicable licenses are listed below. Included components may differ by distribution and platform.

CompixelUI 使用下列第三方库和字体，其用途及适用许可列于下文。不同发行包和平台所包含的组件可能有所不同。

## JVM libraries / JVM 库

| Project / 项目 | Components / 组件 | License / 许可 |
| --- | --- | --- |
| AndroidX | Annotations, architecture components, collections, Compose runtime, lifecycle, navigation events, saved state | Apache-2.0 |
| JetBrains Compose Multiplatform | Compose UI and AndroidX ports / Compose UI 与 AndroidX 移植库 | Apache-2.0 |
| Kotlin | Standard library / 标准库 | Apache-2.0 |
| Kotlinx | Coroutines, Serialization, atomicfu / 协程、序列化及 atomicfu | Apache-2.0 |
| JetBrains | Annotations and JBR API / 注解库及 JBR API | Apache-2.0 |
| JSpecify | Nullness annotations / 空值性注解 | Apache-2.0 |
| [Skiko](https://github.com/JetBrains/skiko) / [Polyfrost Skiko](https://github.com/Polyfrost/skiko) | Skia bindings, including the Polyfrost fork with Vulkan support / Skia 绑定，含提供 Vulkan 支持的 Polyfrost 分支 | Apache-2.0 |

These libraries are distributed under the [Apache License, Version 2.0](licenses/common/Apache-2.0.txt). Their accompanying copyright and NOTICE files form part of this distribution.

上述库按 [Apache License 2.0](licenses/common/Apache-2.0.txt) 分发，其随附的版权及 NOTICE 声明亦属于本发行包的一部分。

Skiko includes code adapted from the Android Open Source Project. See its [NOTICE](licenses/runtime/skiko/NOTICE.txt).

Skiko 包含改编自 Android Open Source Project 的代码，详见其 [NOTICE](licenses/runtime/skiko/NOTICE.txt)。

## Native graphics libraries / 原生图形库

The Skiko native libraries include Skia and supporting components with the following license terms.

Skiko 原生库包含 Skia 及辅助组件，适用许可如下。

| Component / 组件 | Use / 用途 | License / 许可 |
| --- | --- | --- |
| Skia | 2D graphics, text and image rendering through Skiko / 通过 Skiko 提供二维图形、文字与图像渲染 | [BSD-3-Clause](licenses/runtime/skia/LICENSE.txt) |
| FreeType | Skia uses the FreeType Team's font engine for font loading and glyph rasterization / Skia 使用 FreeType 团队提供的字体引擎加载字体并栅格化字形 | [FreeType License (FTL)](licenses/runtime/skia/freetype/LICENSE.txt) |
| HarfBuzz | Text shaping / 文本字形塑形 | [Old MIT](licenses/runtime/skia/harfbuzz/LICENSE.txt), [Microsoft shaping data / 字形数据](licenses/runtime/skia/harfbuzz/LICENSE-ms-use.txt) |
| ICU and Unicode data / ICU 与 Unicode 数据 | Unicode text processing / Unicode 文本处理 | [Unicode License V3 and additional notices / 附加声明](licenses/runtime/skia/icu/LICENSE.txt) |
| libjpeg-turbo / Independent JPEG Group | JPEG image encoding and decoding in Skia / Skia 中的 JPEG 图像编解码 | [License / 许可](licenses/runtime/skia/libjpeg-turbo/LICENSE.txt), [IJG](licenses/runtime/skia/libjpeg-turbo/README.ijg), [NOTICE / 组件声明](licenses/runtime/skia/libjpeg-turbo/NOTICE.txt) |
| libpng | PNG image encoding and decoding / PNG 图像编解码 | [libpng](licenses/runtime/skia/libpng/LICENSE.txt) |
| libwebp | WebP image encoding and decoding / WebP 图像编解码 | [BSD](licenses/runtime/skia/libwebp/LICENSE.txt), [patent grant / 专利授权](licenses/runtime/skia/libwebp/PATENTS), [authors / 作者](licenses/runtime/skia/libwebp/AUTHORS) |
| zlib | Compression of image and document data / 图像与文档数据压缩 | [zlib](licenses/runtime/skia/zlib/LICENSE.txt) |
| Expat | XML parsing for SVG content / SVG 内容的 XML 解析 | [MIT](licenses/runtime/skia/expat/LICENSE.txt) |
| Brotli | Brotli-compressed data processing / Brotli 压缩数据处理 | [MIT](licenses/runtime/skia/brotli/LICENSE.txt) |
| D3D12 Memory Allocator | Direct3D 12 memory allocation / Direct3D 12 显存分配 | [MIT](licenses/runtime/skia/d3d12-memory-allocator/LICENSE.txt) |
| Vulkan Memory Allocator | Vulkan memory allocation / Vulkan 显存分配 | [MIT](licenses/runtime/skia/vulkan-memory-allocator/LICENSE.txt) |
| Vulkan Headers | Vulkan API declarations / Vulkan API 声明 | [Apache-2.0 / MIT](licenses/runtime/skia/vulkan-headers/LICENSE.txt), [MIT text / 正文](licenses/runtime/skia/vulkan-headers/LICENSE-MIT.txt) |
| SPIR-V Headers | SPIR-V instruction definitions / SPIR-V 指令定义 | [MIT and per-file notices / 文件级声明](licenses/runtime/skia/spirv-headers/LICENSE.txt) |
| SPIR-V Tools | SPIR-V shader processing / SPIR-V 着色器处理 | [Apache-2.0](licenses/runtime/skia/spirv-tools/LICENSE.txt) |
| SPIRV-Cross | Shader translation / 着色器转换 | [Apache-2.0](licenses/runtime/skia/spirv-cross/LICENSE.txt) |
| Piex | Preview extraction from RAW images / RAW 图像预览提取 | [Apache-2.0](licenses/runtime/skia/piex/LICENSE.txt) |
| Adobe DNG SDK (macOS) | DNG/RAW image decoding in Skia's macOS native libraries / Skia macOS 原生库中的 DNG/RAW 图像解码 | [Adobe DNG SDK License Agreement](licenses/runtime/skia/dng-sdk/LICENSE.txt) |

## Font / 字体

CompixelUI uses the unmodified **Monocraft** font by Idrees Hassan for Ore-style interface text, under the **SIL Open Font License 1.1**. Its full license and copyright notice accompany the font as `Monocraft-LICENSE.txt`.

CompixelUI 使用 Idrees Hassan 创作的 **Monocraft** 字体显示 Ore 风格界面文字，字体未经修改，适用 **SIL Open Font License 1.1**。完整许可及版权声明随字体附于 `Monocraft-LICENSE.txt`。
