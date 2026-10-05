# 进出场动画

[English](../en/transitions.md) · [全部指南](../README.md)

`ScreenTransition` 在界面打开时为内容播放入场动画，在界面关闭时播放退场动画。它在 `ComposeScreen`、`ComposeMenuScreen` 和 `ComposeInventoryScreen` 中的用法相同，不需要其他代码。

## 为界面添加动画

把内容包在 `ScreenTransition` 中，并传入 Compose 的入场和退场过渡。下面是[快速开始](getting-started.md#3-打开界面)中的计数器：

```kotlin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import dev.compixel.host.ScreenTransition

@Composable
override fun Content(state: Int) {
    ScreenTransition(
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + slideOutVertically { it / 8 },
    ) {
        OreScreen("Counter", maxWidth = 160.dp, maxHeight = 78.dp) {
            OreText("Clicked $state times")
            OreButton("Click me", onClick = { send(Unit) })
        }
    }
}
```

过渡可以用 `+` 组合。入场和退场可以不同，默认都是淡入淡出。界面从第一帧起就接受输入，入场动画播放期间也是如此。

## 单独为部分内容添加动画

在 `ScreenTransition` 内，`Modifier.animateEnterExit` 可以为某一部分加上它自己的过渡，作用域中的 `transition` 可以驱动自定义效果。一个界面也可以使用多个 `ScreenTransition`。下面的例子中，背景淡入淡出，窗口从下方滑入滑出：

```kotlin
ScreenTransition(enter = fadeIn(), exit = fadeOut()) {
    Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
        OreSurface(
            Modifier.animateEnterExit(
                enter = slideInVertically { it / 2 },
                exit = slideOutVertically { it / 2 },
            )
        ) {
            OreText("Hello")
        }
    }
}
```

## 界面关闭时

无论是玩家按下 Esc、内容调用 `requestClose()`，还是服务端关闭了菜单，玩家都会立即重新获得控制权：下一个界面随即打开，或者游戏立即重新接收输入，同时内容播放退场动画。

- 退场动画绘制在游戏画面之上、随后打开的界面之下，不接收输入。
- 内容继续显示最后一次快照，`send` 返回 `false`。容器界面中的槽位继续显示最后的物品。
- 所有退场动画结束后，界面释放它的 Compose 会话和图形资源。
- 在退场期间再次打开同一个界面对象时，退场动画停止，界面从头入场。新的界面（例如再次打开的同一个容器）会正常入场，旧界面的退场动画在它下方播完。
- 没有使用 `ScreenTransition` 的界面会立即关闭。
- 在 [HUD 层](hud.md)中，`ScreenTransition` 只播放入场动画；HUD 层停止时立即消失。

## 被覆盖的界面

配方查看器等界面可以在菜单保持打开时覆盖容器界面。被覆盖的界面会保留它的内容：`remember` 的状态、滚动位置和输入的文字都保持不变，再次显示时也不会重新入场。返回时它会读取新的快照。

如果菜单在界面被覆盖期间关闭，或者覆盖它的界面关闭后没有回到它，界面会直接释放会话，不播放退场动画，并调用 `menuClosed()`。

`ComposeScreen` 没有菜单可以表明它是否还会再次显示，所以替换它的界面会关闭它：它在新界面下方播放退场动画，再次显示时重新入场。

## 在较长的入场期间忽略输入

如果要在较长的入场动画结束前忽略点击，可以检查作用域中的 `transition`：

```kotlin
ScreenTransition(enter = fadeIn(tween(800))) {
    val entered = transition.currentState == EnterExitState.Visible
    OreButton("Start", onClick = { if (entered) send(Unit) })
}
```
