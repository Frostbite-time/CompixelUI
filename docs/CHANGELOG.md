# CompixelUI 0.1.10-alpha.1

- Screens and HUD layers now own the game state their content shows. `ComposeScreen<S, A>`, `ComposeMenuScreen<M, S, A>`, `ComposeInventoryScreen<M, S, A>` and `ComposeHudLayer<S>` are abstract: override `snapshot` to read the game on the game thread, `Content` to draw the latest snapshot, and `handle` to run the actions the content sends with `send`. HUD layers have no actions. See [Show game data](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#4-show-game-data).
- The host takes the first snapshot before the first frame, handles each tick's actions before the next snapshot, stops once `handle` closes the screen, and starts again from a new snapshot when the screen returns, for example from a recipe viewer. HUD layers take a snapshot every client tick.
- Breaking: the `content` lambda parameter is gone. Move the content into a subclass's `Content`; a screen without game state extends `ComposeScreen<Unit, Nothing>`.
- Add `requestClose()` to Compose screens, so content can close its screen from the Compose thread.
- Add `menuClosed()` to `ComposeMenuScreen` and `ComposeInventoryScreen`. It runs once when the screen closes for good, not while a recipe viewer covers it, so consumers no longer need to check which menu is still open.
- A `ComposeInventoryScreen` for the player's own inventory menu now releases its slots when it closes; before, it treated every close like a recipe viewer covering it.
- The built-in config screen uses the same state handling.
