# 资源图片

[English](../en/resources.md) · [全部指南](../README.md)

在 Compose 中直接使用 Minecraft 资源路径。CompixelUI 从当前资源包中读取图片，资源重载时自动更新。

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

## 共享内容和自定义宿主

不依赖 Minecraft 的共享内容可以导入 `dev.compixel.host.resources.painterResource`，使用带命名空间的完整字符串路径。Minecraft 宿主会自动提供它的 `LocalResources`。

自定义宿主通过 `LocalResources` 提供 `UiResources`，并用自己的 `ResourceSource` 读取文件。资源源返回编码后的文件字节，路径不存在时返回 null。资源变化后，在宿主的 UI 线程调用 `UiResources.reload()`。
