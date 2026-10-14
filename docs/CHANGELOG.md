# CompixelUI 0.1.10

This release combines the changes developed in 0.1.10-alpha.1 through 0.1.10-alpha.17. Those development versions were not released separately.

## 0.1.10 changes

- Changed: screens and HUD layers own the game state their content shows through immutable snapshots. Screen actions sent during mouse and keyboard input run before the event returns, so the next frame shows the result. Breaking: screens and HUD layers are now abstract; replace the old content lambda with a subclass that implements `snapshot`, `Content` and, for screens with actions, `handle`. See [Show game data](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#4-show-game-data).
- Added: `ScreenTransition` animates screens in and out, and dialogs fade with their screen through `LocalOverlayVisibility`. Closing returns control to the player while the exit plays above the game. Container screens covered by recipe viewers retain their Compose state, and `menuClosed()` runs when their menu closes. Every Minecraft adapter uses the shared `UiLayer` for its screens and HUD layers. See [Screen transitions](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/transitions.md).
- Added: mod-owned color schemes and an in-game color editor with live previews, saved player choices and resource-pack export. CompixelUI's Config button in the mod list opens the editor for its own Ore schemes. Breaking: `ColorSchema` and schemes replace the old theme API and file format. Mods can supply their own `UiDesign`, colors and preview. See [Color schemes](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md).
- Changed: Ore uses the bundled Compixel font, preserving Monocraft's glyphs and adding Unifont pixel glyphs for Chinese, Japanese, Korean and other supported Minecraft languages. Emoji, unsupported scripts and rare characters continue to use system fonts. Stray control characters no longer draw boxes. See [Colors and text](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/ore-ui.md#colors-and-text).
- Changed: menu synchronization uses one shared implementation across Minecraft versions. Large values and states arrive in batches and apply atomically; state starts immediately after the menu opens, and transfers advance as received bytes are acknowledged. Breaking: synchronization settings, size limits, rate budgets and timeouts are removed; bind with `MenuSync.bind(menu, schema)`. Actions still declare their maximum size, and the server accepts at most 16 new actions per tick. The wire protocol is version 6, so both sides need this version of CompixelUI. See [Menu synchronization](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/menu-sync.md).
- Added: custom slot content and overlays that retain native container gestures and tooltips. Ore slot amounts use white digits with a pixel shadow, with editable colors and the shared `OreSlotAmount` control. A slot has one hover state, and its tooltip stays visible while a mouse button is held. Breaking: `MenuSlotState.highlighted` and `ComposeMenuSlots.interacting` are removed. See [Container screens](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/inventory.md).
- Changed: `NativeItemOptions.preparationsPerFrame` accepts up to 1024 preparations, allowing several atlas pages to be drawn in one frame when many icons become visible. See [Item icons](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/items.md).
- Fixed: the color editor fits short screens and keeps its preview scrollable. The color picker retains its chosen hue, saturation and brightness, avoiding pointer jitter and keyboard drift even when changes return from the host a few frames later. Ore's pencil glyph is easier to recognize at small sizes.
- Removed: the built-in configuration editor and its `ComposeConfigScreen` and `ConfigEditor` APIs. Consumers use their loader's configuration screen or a configuration library. Unused session and design APIs, including `UiSession.setActive`, `SessionState.SUSPENDED`, `UiSession.diagnosticThread()` and `UiDesign.None`, are also removed.

## Unreleased development history

These entries describe each development version as it existed at the time. The temporary theme API introduced in alpha.4 was replaced by color schemes in alpha.10, and alpha.8's background warm-up was reverted.

### 0.1.10-alpha.17

- Fixed: a container slot's tooltip stays visible while a mouse button is held, as in a vanilla container. It hid on every press, so it flickered while shift-clicking stacks out of a storage.
- Breaking: `ComposeMenuSlots.interacting` is removed. It only told whether a button was held over the slots.

### 0.1.10-alpha.16

- Breaking: menu sync has no settings anymore. `MenuSyncOptions`, `SyncLimits`, `ActionLimits` and `TransferBudget` are gone, and `MenuSync.bind(menu, schema)` is the only way to bind a menu. `SyncCodecs.string(maxBytes)` is now `SyncCodecs.STRING`, and `MinecraftSyncCodecs.registry` (`buffer` on Forge 1.20.1) no longer takes a size. An action still declares its largest value with `MenuAction.of(id, codec, maxBytes, handler)`. See [Menu synchronization](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/menu-sync.md).
- Changed: values of any size synchronize. A state or a single value larger than any Minecraft packet arrives in batches and takes effect all at once, and nothing in menu sync times out.
- Changed: menu sync paces a menu's state by what the client has received: at most 512 KiB that the client has not acknowledged is on its way, with no rate budget. Large states reach fast connections much sooner, and on slow connections other game traffic keeps moving. An action sent in parts waits in the same way for the server to confirm what arrived.
- Changed: a menu's state follows its opening at once instead of after a reply from the client, so the menu has its data one round trip sooner.
- Removed: the `ActionFailure` values `QUEUE_BYTES`, `PENDING_ACTIONS`, `QUEUE_TIMEOUT`, `SEND_TIMEOUT`, `REPLY_TIMEOUT` and `ASSEMBLY_TIMEOUT`, and `ActionStatus.EXPIRED`.
- Changed: the menu sync protocol version is now 6, so the client and the server need this version of CompixelUI.

### 0.1.10-alpha.15

- Fixed: dragging through the dark part of `OreColorPicker`'s plane no longer shakes the pointer sideways, and arrow-key steps no longer drift. The picker keeps the hue, saturation and brightness it chose instead of reading them back from the 8-bit color, including while a host returns the value a few frames later; a color it did not choose, such as a typed hex value or a restored default, still moves the pointer to that color.

### 0.1.10-alpha.14

- Changed: `OreGlyph.Pencil` is drawn as a pencil, with an eraser, a body and a sharpened point parted by gaps, so it reads as one at small sizes instead of a slanted bar.

### 0.1.10-alpha.13

- Changed: slot amounts in Ore's look are white digits over a one-pixel shadow, like vanilla item counts, about two thirds of vanilla's size. They stay legible over light and dark items in every scheme, and the decimal point takes vanilla's narrow width, so amounts such as `1.2K` fit the slot.
- Added: the Ore colors `slotAmount` and `slotAmountShadow` set the digits and their shadow, and can be changed in the color editor. See [Ore color names](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#ore-color-names).
- Added: `OreSlotAmount` draws an amount the same way in slots you draw yourself. See [Draw slots yourself](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/inventory.md#draw-slots-yourself).

### 0.1.10-alpha.12

- Fixed: the color editor fits short screens, such as 1080p at Minecraft's automatic GUI scale. A design's preview keeps its size and scrolls instead of being squeezed, and the picker gets shorter so the color list keeps its rows. See [Color schemes for mod developers](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#for-mod-developers).
- Changed: the color editor has no bottom bar. Export sits next to the scheme list, Restore all next to Restore, and the export dialog shows the result.
- Added: `OreColorPicker(planeHeight = …)` sets the height of the saturation and brightness plane.

### 0.1.10-alpha.11

- Added: CompixelUI's Config button in the mod list opens the color editor for CompixelUI's own color schemes, which screens in plain Ore style use. Players can switch them and change any color without a mod offering its own entry. See [Change colors in game](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#change-colors-in-game).

### 0.1.10-alpha.10

- Added: a color editor. Players switch color schemes and change any color, including its opacity, while a preview of the mod's screens shows the result, and every screen of that mod follows at once. Their choices are saved in `config/compixel-colors.json`. Screens offer it through `LocalColorEditor`, which mods call from a control of their own; other code opens `ColorEditorScreen`. See [Change colors in game](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#change-colors-in-game).
- Added: the editor exports the player's colors as a resource pack that replaces a scheme or adds a new one, and can make it the default, ready to share or to include in a modpack. See [Export for a resource pack or modpack](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#export-for-a-resource-pack-or-modpack).
- Changed: themes are replaced by color schemes, which belong to each mod. A scheme file at `assets/<namespace>/compixel/schemes/<name>.json` sets `colors` and may `extends` another scheme of the mod, and `assets/<namespace>/compixel/schemes.json` chooses the default scheme and the editor's order. Theme files under `compixel/themes/` are no longer read. See [Write a scheme file](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#write-a-scheme-file).
- Changed: a design system declares its colors as a `ColorSchema`: base colors, and colors that follow others through a rule until a file or the player sets them. Code reads a color as `colors[role]`, for example `OreTheme.colors[OreColors.panel]`. A `UiDesign` names the `owner` of its schemes and gives the editor a `preview()`. `OreDesign` is now a class, and `OreDesign("examplemod")` gives a mod's Ore screens schemes of their own. `ThemeId`, `ThemeSection`, `ThemeCatalog`, the `theme` parameters of screens and HUD layers, and Ore's `palette` and `preset` are removed. See [For mod developers](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#for-mod-developers).
- Changed: Ore's colors include the scrim behind dialogs and the selected text in text fields, and `OreTheme` gives text fields Ore's selection colors and a right-click menu in Ore's style.

### 0.1.10-alpha.9

- Added: `CompixelGuiLayers.SCREEN_EXITS` names the HUD layer in which closed screens play their exit. Register a layer of your own below it to draw beneath closing screens, for example an effect that should last until their exit has finished. See [Screen transitions](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/transitions.md#when-the-screen-closes).
- Added: `slots.Slot(id, overlay = { slot -> ... })` keeps Ore's look and draws a mark of your own above it, with the same slot state as a slot you draw yourself. See [Container screens](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/inventory.md#draw-slots-yourself).
- Changed: `NativeItemOptions.preparationsPerFrame` accepts up to 1024. An atlas page still holds up to 64 icons; a larger budget draws several pages in one frame, so a screen that scrolls through many icons can draw them all at once. See [Item icons](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/items.md).
- Changed: a slot has one hover state. `MenuSlotState.hovered` marks the slot a click would reach: it follows Minecraft's pointer, also while a button is held, and is false while the screen's slot interactions are disabled. Slots in Ore's look light up by the same state. `highlighted` is removed: it was described as a drag highlight but marked the hovered slot by another route, so the two could disagree while dragging.

### 0.1.10-alpha.8 (reverted)

- Experimental: background Compose warm-up was added to reduce the pause when opening the first screen. The change was reverted before release and is not included in 0.1.10.

### 0.1.10-alpha.7

- Changed: Ore text uses the new bundled Compixel font. It keeps Monocraft's glyphs and adds GNU Unifont, which Minecraft also uses, for the other characters of Minecraft's languages. Chinese, Japanese, Korean and other scripts now appear as pixel text on every computer instead of in the system's fonts. Emoji, scripts that pixel glyphs cannot lay out, such as Devanagari, and rare characters still use the system's fonts. See [Ore UI](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/ore-ui.md#colors-and-text).
- Changed: the font makes the mod files about 2 MB larger.
- Fixed: a stray control character in text, such as a carriage return, no longer draws a box.

### 0.1.10-alpha.6

- New: `ScreenTransition` animates a screen's content in when the screen opens and out when it closes, using Compose's enter and exit transitions, which can differ. Closing gives the player control back at once while the exit plays above the game. See [Screen transitions](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/transitions.md).
- New: `LocalOverlayVisibility` gives popups and dialogs, which draw in layers of their own, the screen's visibility during its transition. Ore's dialogs fade with the screen, scrim included.
- Changed: a container screen that a recipe viewer or another screen covers while its menu stays open keeps its Compose session. When it shows again, its content is as it was, including `remember` state, scroll positions and typed text, and its entrance doesn't play again.
- Fixed: `menuClosed()` now runs when the menu closes while another screen covers the container screen.
- Fixed: an enter animation that starts with a screen's content, such as `AnimatedVisibility` with a `MutableTransitionState`, now plays from the first frame instead of jumping to its end.

### 0.1.10-alpha.5

- Breaking: CompixelUI no longer ships a config screen. `ComposeConfigScreen`, `ConfigEditor` and their translations are removed, because editing config files is not a UI framework's job. On NeoForge, register NeoForge's own screen from your client mod constructor with `container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new)`. Forge 1.20.1 has no built-in config screen: use a config library's screen, or let players edit the config files.
- Breaking: removed unused API. `UiSession.setActive`, `SessionState.SUSPENDED`, `UiSession.diagnosticThread()` and `UiDesign.None` are gone, and a `UiLayerSurface` always takes its item, tooltip and drawing image sources.

### 0.1.10-alpha.4

- Breaking: theme files moved to `assets/<namespace>/compixel/themes/<path>.json`, and Ore's `preset`, `palette` and `colors` now sit in an `"ore"` section next to `"format": 1`. A mod can add a section of its own to the same files with `ThemeSection`, so resource packs recolor the parts it draws itself without borrowing Ore's color names. An invalid section is ignored on its own, while the file's other sections still apply. See [Themes](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md).
- Screens and HUD layers take a `design`, Ore by default, which sets up a design system around their content; `UiDesign.None` shows content without one. Any control can give the native click sound, as Ore's buttons do, through `LocalUiFeedback`. See [Use your own design system](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#for-mod-developers).
- Slots can take a look of their own: `slots.Slot(id, Modifier.size(18.dp)) { slot -> … }` draws from a `MenuSlotState` with the icon, amount labels, marking, drag highlight and hover, while clicks, drags, shift-clicks and tooltips work as before. `slots.Slot(id)` keeps Ore's look. See [Draw slots yourself](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/inventory.md#draw-slots-yourself).
- Screens and HUD layers of every Minecraft version now run one shared Compose layer, `UiLayer` in `host`, with the same behavior. Hosts outside Minecraft can build on it too: it owns the session, frames, input, clipboard, feedback, themes and the content's actions, and a subclass supplies the window, renderer and native images.
- Breaking: the new `ui-core` module holds what design systems share. `OreThemeId` is now `dev.compixel.ui.theme.ThemeId`, `OreThemeCatalog` and `OreThemeResources` became `ThemeCatalog` and `LocalThemeCatalog`, and `OreFeedback` and `OreTheme`'s `feedback` parameter became `UiFeedback` and `LocalUiFeedback`.

### 0.1.10-alpha.3

- Breaking: menu synchronization's client and server sessions moved into the shared `menu-sync` module, so all Minecraft versions run one implementation; the wire format is unchanged. `MenuSync.Status` is now `dev.compixel.sync.session.SyncStatus`, `MenuSync.Statistics` is `SyncStatistics`, and `MenuSync.ActionStatus` and `MenuSync.ActionResult` are `ActionStatus` and `ActionResult` in `dev.compixel.sync.action`. `MenuSync.bind`, `MenuAction.of` and the rest of the binding work as before. See [Menu synchronization](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/menu-sync.md).

### 0.1.10-alpha.2

- Actions that content sends during a click, key press or typed character are now handled before that input event returns, as vanilla buttons act, and a new snapshot follows, so the next frame already shows the result. Before, they waited for the next client tick: up to 50 ms, or longer while a server slows ticks with `/tick rate`. Clicks no longer sound before the screen responds, and dragged sliders and scrollbars bound to the snapshot move every frame. Actions sent at other times, for example from a coroutine, still wait for the next tick. See [Show game data](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#4-show-game-data).
- `requestClose()` called during a click now closes the screen before the click returns. Because a click's actions run at once, pressing Escape or opening a recipe viewer right after a click no longer discards them.
- `snapshot` can now run more than once per tick, so it should only read the game.
- When `send` is refused because 64 actions are already waiting, the screen logs a warning once per session instead of dropping the action silently.

### 0.1.10-alpha.1

- Screens and HUD layers now own the game state their content shows. `ComposeScreen<S, A>`, `ComposeMenuScreen<M, S, A>`, `ComposeInventoryScreen<M, S, A>` and `ComposeHudLayer<S>` are abstract: override `snapshot` to read the game on the game thread, `Content` to draw the latest snapshot, and `handle` to run the actions the content sends with `send`. HUD layers have no actions. See [Show game data](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/getting-started.md#4-show-game-data).
- The host takes the first snapshot before the first frame, handles each tick's actions before the next snapshot, stops once `handle` closes the screen, and starts again from a new snapshot when the screen returns, for example from a recipe viewer. HUD layers take a snapshot every client tick.
- Breaking: the `content` lambda parameter is gone. Move the content into a subclass's `Content`; a screen without game state extends `ComposeScreen<Unit, Nothing>`.
- Add `requestClose()` to Compose screens, so content can close its screen from the Compose thread.
- Add `menuClosed()` to `ComposeMenuScreen` and `ComposeInventoryScreen`. It runs once when the screen closes for good, not while a recipe viewer covers it, so consumers no longer need to check which menu is still open.
- A `ComposeInventoryScreen` for the player's own inventory menu now releases its slots when it closes; before, it treated every close like a recipe viewer covering it.
- The built-in config screen uses the same state handling.
