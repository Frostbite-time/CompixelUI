# Documentation images / 文档图片

[English build guide](../en/build-and-test.md) · [中文构建指南](../zh-CN/build-and-test.md)

These assets are direct captures from the current implementation, without compositing or image editing. Component pages come from the offscreen desktop renderer; native item/slot pages come from a packaged Minecraft 1.21.1 OpenGL client. The latter use the preview's English locale; both language guides provide localized captions.

这些资源直接捕获自当前实现，没有拼接或图像编辑。组件页来自桌面离屏渲染器，原生物品/槽位页来自打包后的 Minecraft 1.21.1 OpenGL 客户端。后者使用预览的英文语言设置，两种语言指南均提供对应说明。

## Recreate / 重新获取

Run from the repository root. The desktop task stays headless; the Windows game helper uses a hidden, isolated desktop.

从仓库根目录运行。桌面任务采用无窗口渲染，Windows 游戏脚本使用隐藏的隔离桌面。

```powershell
.\gradlew.bat '-PcomposemcTargets=none' :desktop:smoke
.\tools\run_background_benchmark.ps1 -Minecraft 1.21.1 -Backend opengl -Label docs-refresh -Frames 120
```

After successful completion, inspect the captures and copy the following files into this directory. Use complete images and keep captions accurate if the source changes.

成功完成后，检查截图并将下列文件复制到本目录。使用完整图片，来源变化时同步修正说明。

| Destination / 目标 | Generated source / 生成来源 |
| --- | --- |
| `choices-en.png` / `choices-zh-CN.png` | `desktop/build/screenshots/ore-selection-en.png` / `ore-selection-zh-150.png` |
| `menus-en.png` / `menus-zh-CN.png` | `desktop/build/screenshots/ore-menus-en.png` / `ore-menus-zh-150.png` |
| `tooltips-en.png` / `tooltips-zh-CN.png` | `desktop/build/screenshots/ore-tooltips-en.png` / `ore-tooltips-zh-150.png` |
| `windows-en.png` / `windows-zh-CN.png` | `desktop/build/screenshots/ore-windows-en.png` / `ore-windows-zh-150.png` |
| `native-items.png` | `minecraft/neoforge-1.21.1/build/benchmark-opengl-background-docs-refresh/benchmark-results/1-ore-items.png` |
| `native-slots.png` | `minecraft/neoforge-1.21.1/build/benchmark-opengl-background-docs-refresh/benchmark-results/1-ore-slots.png` |

The screenshots illustrate UI behavior; they are not performance charts. Fonts and native game assets retain their respective licenses. See the [project credits](../../README.md) / [项目致谢](../../README.zh-CN.md).

截图用于展示界面行为，不是性能图表。字体和原生游戏资源保留各自的许可归属，详见上方项目致谢链接。
