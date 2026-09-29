# CompixelUI 0.1.2

- Compose MC is now called CompixelUI. The mod ID is `compixel`, the packages are under `dev.compixel`, and the Maven artifacts are `dev.compixel:compixel-*`. Mods built for Compose MC need to update their dependency, imports and theme folders (`assets/<namespace>/compixel/ore_themes/`); players only need to replace the file.
- Fixed a GPU memory leak that eventually collapsed the frame rate on screens with continuously redrawn item icons, such as enchanted items or items drawn by custom renderers.
- Renderer statistics now include `retiredNativeImages`, released images that Compose still draws, and `strandedNativeImages`, images still referenced when a renderer closed.
