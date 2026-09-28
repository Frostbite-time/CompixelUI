# Compose MC 0.1.2

- Fixed a GPU memory leak that eventually collapsed the frame rate on screens with continuously redrawn item icons, such as enchanted items or items drawn by custom renderers.
- Renderer statistics now include `retiredNativeImages`, released images that Compose still draws, and `strandedNativeImages`, images still referenced when a renderer closed.
