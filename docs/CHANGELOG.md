# CompixelUI 0.1.6

- Compose screens and HUDs now present at framebuffer pixel size when the window dimensions are not multiples of the GUI scale, keeping the whole interface sharp. This fixes every rendering backend on Minecraft 26.1.2, 26.2 and 26.3, and the CPU reference backend on 1.20.1 and 1.21.1.
- On Minecraft 26.1.2, 26.2 and 26.3, native slots, carried items, tooltips and IME candidate areas follow the same pixel coordinates as the Compose frame, while mouse input keeps Minecraft's GUI coordinate mapping.
- On Minecraft 26.1.2, 26.2 and 26.3, ordinary item models are rasterized at the requested pixel size instead of resampled from Minecraft's integer-scale GUI item cache. Larger previews keep their sharp edges; oversized models retain Minecraft's dedicated rendering path.
- On Minecraft 26.1.2, 26.2 and 26.3, config screens and the development F8 preview use normal screen switching without an extra immediate render. The F8 tick handler opens the preview only from the game view.
- NeoForge 26.2 and 26.3 metadata now declares both a mod list banner and a square icon, resolving the deprecated `logoFile` warning with the configured loaders while retaining the logo fallback for older supported loaders.
- Repository formatting skips build outputs, run directories and local caches during file discovery, reducing unnecessary directory scans without changing the formatting rules or source coverage.

## Included changes from 0.1.5

- Each item icon is drawn at the pixels it is laid out with, so icons of any size, not only 16 dp ones, are shown pixel for pixel. An icon shown at two sizes is drawn at both.
- The default `NativeItemOptions.imageSize`, `null`, now follows each icon's size instead of the size of a 16 dp icon. A fixed size still draws every icon once and resamples it on screen.
- When an icon's size keeps changing, as in an animation, it shows its nearest drawn size until the new size has held for two frames.
- On Minecraft 26.1.2, 26.2 and 26.3, item icons no longer depend on the window size, which scaled them slightly when the window was not a multiple of the GUI scale. Icons larger than 16 dp are drawn at no less than their own resolution instead of enlarged from Minecraft's 16 dp item cache.
- On Minecraft 26.1.2, 26.2 and 26.3, a hovered slot in a `ComposeInventoryScreen` shows only its own hover state. Minecraft's slot highlight no longer covers the slot and its item, as on 1.20.1 and 1.21.1.
- An atlas page holds icons of one size. Icons shown at 16 dp take the same GPU memory as before; larger icons take more, with the square of their size.
