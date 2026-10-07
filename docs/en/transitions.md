# Screen transitions

[简体中文](../zh-CN/transitions.md) · [All guides](../README.md)

`ScreenTransition` animates a screen's content in when the screen opens and out when it closes. It works the same in `ComposeScreen`, `ComposeMenuScreen` and `ComposeInventoryScreen`, and needs no other code.

## Animate a screen

Wrap the content in `ScreenTransition` and pass Compose's enter and exit transitions. This is the counter from [Getting started](getting-started.md#3-open-a-screen):

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

Combine transitions with `+`. The entrance and the exit can differ; both fade by default. The screen takes input from its first frame, while it is still entering.

## Animate parts on their own

Inside `ScreenTransition`, `Modifier.animateEnterExit` adds a part's own transition, and the scope's `transition` drives effects of your own. A screen can also use several `ScreenTransition`s. Here the backdrop fades while the window slides in from below:

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

## When the screen closes

Whether the player presses Escape, the content calls `requestClose()` or the server closes the menu, the player gets control back at once: the next screen opens, or the game takes input again, while the content plays its exit.

- The exit draws above the game view and beneath any screen that opens next. It takes no input.
- Exits draw in the HUD layer `CompixelGuiLayers.SCREEN_EXITS`, above the other HUD layers. Register a layer of your own below it to draw beneath closing screens, for example an effect that should last until their exit has finished.
- The content keeps showing its last snapshot, and `send` returns `false`. In a container screen, slots keep showing their last items.
- When every exit has finished, the screen releases its Compose session and its graphics.
- Opening the same screen object during its exit stops the exit, and the screen enters from the start. A new screen, such as the same container opened again, enters while the old exit finishes beneath it.
- An open Ore dialog fades with the screen, scrim included. Ore's menus and tooltips close at once.
- A screen without `ScreenTransition` closes at once, and so does any screen while there is no world, such as on the title screen.
- In a [HUD layer](hud.md), `ScreenTransition` only enters; the layer disappears at once when it stops.

## Popups and dialogs of your own

Popups and dialogs draw in layers of their own, which the transition's modifiers don't reach. Inside a `ScreenTransition`, `LocalOverlayVisibility` gives them the screen's visibility from 0 to 1; apply it as their alpha:

```kotlin
import androidx.compose.ui.graphics.graphicsLayer
import dev.compixel.ui.LocalOverlayVisibility

Popup(onDismissRequest = { menuOpen = false }) {
    val visibility = LocalOverlayVisibility.current?.animate()
    MyMenu(Modifier.graphicsLayer { alpha = visibility?.value ?: 1f })
}
```

The value is 1 while the screen shows, so a popup that opens then appears at once. It fades with the screen's entrance and exit, and the exit waits for it. For a Compose `Dialog`, also multiply the alpha of its `scrimColor` by the value, as Ore's dialogs do.

## Covered screens

A recipe viewer or another screen can cover a container screen while its menu stays open. The covered screen keeps its content: `remember` state, scroll positions and typed text stay, and it doesn't enter again when it shows again. It takes a new snapshot when it returns.

If the menu closes while the screen is covered, or the covering screen closes without returning to it, the screen releases its session without an exit, and `menuClosed()` runs.

A `ComposeScreen` has no menu that tells whether it will come back, so a screen that replaces it closes it: it plays its exit beneath the new screen and enters again when it is shown again.

## Ignore input during a long entrance

To ignore clicks until a long entrance has finished, check the scope's `transition`:

```kotlin
ScreenTransition(enter = fadeIn(tween(800))) {
    val entered = transition.currentState == EnterExitState.Visible
    OreButton("Start", onClick = { if (entered) send(Unit) })
}
```
