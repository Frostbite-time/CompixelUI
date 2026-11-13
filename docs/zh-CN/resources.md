# 图片与字体资源

[English](../en/resources.md) · [全部指南](../README.md)

在 Compose 中直接使用 Minecraft 资源路径。CompixelUI 从当前资源包中读取图片和字体，资源重载时自动更新。

## 显示图片

将图片放在 `src/main/resources/assets/examplemod/textures/gui/logo.png`，然后使用它的资源路径：

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.compixel.forge.resources.painterResource

@Composable
fun Logo() {
    Image(
        painter = painterResource("examplemod:textures/gui/logo.png"),
        contentDescription = "Logo",
        modifier = Modifier.size(32.dp),
    )
}
```

在 `ComposeScreen`、菜单界面或 `ComposeHudLayer` 中调用即可。宿主自动提供资源系统，弹出菜单和对话框中也可以使用。消费者不需要准备加载器、写 `remember` 或注册重载监听器。

返回值是标准 Compose `Painter`：`Image` 的尺寸、`contentScale`、透明度、`colorFilter`，以及 `Modifier.paint`，都照常工作。固有尺寸就是图片的像素尺寸。PNG、JPEG、WebP 等 Skia 支持的位图格式使用同一个 API。

## 路径

- `examplemod:textures/gui/logo.png` 对应 `assets/examplemod/textures/gui/logo.png`。
- 路径包含扩展名，不包含 `assets/`。
- 省略命名空间时使用 `minecraft`，例如 `"textures/block/stone.png"` 读取原版石头纹理。
- Minecraft、你的模组、其他模组以及资源包中的图片遵循同一套查找规则。

函数也接受游戏的原生资源标识：

| Minecraft | 资源标识 |
| --- | --- |
| 1.20.1 | `ResourceLocation("examplemod", "textures/gui/logo.png")` |
| 1.21.1 | `ResourceLocation.fromNamespaceAndPath("examplemod", "textures/gui/logo.png")` |
| 26.x | `Identifier.fromNamespaceAndPath("examplemod", "textures/gui/logo.png")` |

所有版本都导入 `dev.compixel.forge.resources.painterResource`。

## 缩放

默认使用最近邻采样，让 Minecraft 像素图在缩放后保持清晰。需要平滑缩放插画时：

```kotlin
val painter = painterResource(
    "examplemod:textures/gui/illustration.png",
    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
)
```

## 资源包和重载

Minecraft 选择优先级最高的资源。资源包提供相同的 `assets/<命名空间>/<路径>` 文件，就能替换图片。F3+T 或切换资源包会自动更新图片，包括尺寸变化。Compose 会话保持打开，已经输入的文字、滚动位置和菜单状态都会保留。

资源缺失或无法解码时显示紫黑棋盘，并在日志中给出警告。之后的重载提供了有效文件，图片就会自动恢复。

使用同一路径的可组合项共享一份解码后的图片。最后一个使用者离开组合时，图片移出缓存；已有的录制帧继续保留它的像素。消费者不需要关闭 `Painter` 或图片。

## 替换 Ore 字体

Ore 自动读取 `compixel:font/compixel.ttf`。资源包提供以下文件即可替换自带字体：

```text
assets/compixel/font/compixel.ttf
```

自带字体在模组发布包中仍以 XZ 压缩保存。启动时 CompixelUI 将它解压到一个必需、隐藏且位于 MC 资源包栈底部的内置资源包中。MC 资源管理器对外提供普通 TTF 路径，并统一处理资源包优先级和重载。

在该路径放入有效的 TTF 或 OTF 文件。F3+T 或切换资源包后，Ore 文本、输入框、菜单和对话框会原地更新，保留正在编辑的内容等界面状态。移除资源包后恢复自带字体；替换文件损坏时记录警告并回退到自带字体，下次重载时重试。

这是独立的 UI 字体资源，不会转换 Minecraft 的 `font/*.json` 提供者或位图字形。只修改 MC 原生字体的资源包，还需要提供上述文件才能修改 Ore。

`OreTypography` 中 `fontFamily` 为 null 的样式会继承该资源字体。自定义字号、行高可以保存在长期持有或记忆的 `OreTypography` 中；显式指定的字体族仍由使用方控制。桌面宿主继续使用自带字体。

## 使用其他字体

将字体放在 `assets/examplemod/font/body.ttf`，使用完整资源路径：

```kotlin
import dev.compixel.forge.resources.fontResource

val font = fontResource("examplemod:font/body.ttf")
OreText("Hello", style = OreTheme.typography.body.copy(fontFamily = font))
```

`fontResource` 返回普通 Compose `FontFamily`，可用于 `TextStyle`、输入框或其他设计系统的排版，包括 Material3。它和 `painterResource` 一样接受游戏原生资源标识。文件缺失或损坏时使用 `FontFamily.Default`，也可以通过 `fallback` 指定回退字体。没有资源源的宿主同样使用该回退值。

多个组合共享解码后的字体族。重载后，变化的字体字节会得到新的 Compose 字体标识，已有文本会使用新字体重新测量和绘制。使用方无需关闭返回的字体族。

## 共享内容和自定义宿主

不依赖 Minecraft 的共享内容可以导入 `dev.compixel.ui.resources.painterResource` 或 `dev.compixel.ui.resources.fontResource`，使用带命名空间的完整字符串路径。Minecraft 宿主会自动提供 `LocalResources`。

自定义宿主通过 `LocalResources` 提供 `UiResources`，并用自己的 `ResourceSource` 读取文件。资源源返回编码后的文件字节，路径不存在时返回 null。资源变化后，在宿主的 UI 线程调用 `UiResources.reload()`。这些 UI 契约位于 `ui-core`，宿主只负责文件访问和重载通知。

其他资源类型可以实现 `ResourceType<T>`，通过 `UiResources.load(path, type)` 读取。解码器应使用稳定的实例。组合使用期间，每个路径与类型的组合共享一个解码结果；失败结果也会缓存，重载后重试。解码器及返回值负责其存储的生命周期，缓存淘汰不会关闭仍被使用方或录制帧持有的值。
