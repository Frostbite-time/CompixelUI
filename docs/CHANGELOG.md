# CompixelUI 0.1.3

- Every item icon on screen is now shown, however many a screen displays. Icon atlas pages are added and released as icons appear and disappear, instead of stopping at a fixed cache. `cacheCapacity` now sets how many icons stay cached after they leave the screen.
- An atlas page now redraws only its due icons. One animated item used to redraw all 64 icons on its page and invalidate the drawing of every icon on screen; now it redraws itself alone.
- Enchantment glint and custom item renderers refresh once per game tick instead of every frame, the rate at which Minecraft animates its own textures. `IconRefresh.FRAME` remains available for drawings that must move faster.
- A state change that a frame applies no longer makes the next frame record and render the same picture again. Each item icon refresh used to cost two frames of Compose work.
- `NativeItemStatistics` reports `pages` and `drawnIcons`.
