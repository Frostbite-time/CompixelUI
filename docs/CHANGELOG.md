# CompixelUI 0.1.11

- Fixed: CompixelUI screens open on macOS. Skiko refuses OpenGL on macOS unless it is enabled, so the first screen used to crash the game.
- Added: `CtrlOrCmd`, with `isCtrlOrCmdPressed` for key and pointer events, tells whether the key in Ctrl's role is held: Command on macOS, Ctrl elsewhere, the same key Compose's text fields use. Check Ctrl-style shortcuts with it. See [Menus](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/ore-ui.md#menus).
- Changed: Ore number fields take their large step with Command on macOS. See [Buttons and inputs](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/ore-ui.md#buttons-and-inputs).
- Changed: mouse and wheel events report Ctrl and Command as key events do, with Command as meta. On 1.20.1 and 1.21.1, Command used to arrive as Ctrl on macOS; check such shortcuts with `isCtrlOrCmdPressed` instead.
