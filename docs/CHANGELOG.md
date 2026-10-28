# CompixelUI 0.1.17

This release is for every supported Minecraft version: 1.20.1, 1.21.1, 26.1.2, 26.2 and 26.3. Mods built for the previous release keep working without changes.

- Added: screens and HUD layers can read the game with `gameState` and change it with `gameThread.post`, with no state or action types of their own. A `gameState` property reads the game on the game thread when the screen opens, before its first frame, then every tick or every few ticks, and after work posted from an input event; the content redraws when the value changes. Work posted from a click runs before the click returns. See [Show game data](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#4-show-game-data).
- Deprecated: `snapshot`, `handle` and `send`, which are removed in 0.2.0 together with the screens' state and action type parameters. Until then they keep working and can be mixed with `gameState`. See [Snapshots and actions](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#snapshots-and-actions).
