# CompixelUI 0.1.10-alpha.2

- Actions that content sends during a click, key press or typed character are now handled before that input event returns, as vanilla buttons act, and a new snapshot follows, so the next frame already shows the result. Before, they waited for the next client tick: up to 50 ms, or longer while a server slows ticks with `/tick rate`. Clicks no longer sound before the screen responds, and dragged sliders and scrollbars bound to the snapshot move every frame. Actions sent at other times, for example from a coroutine, still wait for the next tick. See [Show game data](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#4-show-game-data).
- `requestClose()` called during a click now closes the screen before the click returns. Because a click's actions run at once, pressing Escape or opening a recipe viewer right after a click no longer discards them.
- `snapshot` can now run more than once per tick, so it should only read the game.
- When `send` is refused because 64 actions are already waiting, the screen logs a warning once per session instead of dropping the action silently.
