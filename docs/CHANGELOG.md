# CompixelUI 0.1.4

- Item icons are drawn at the pixel size they are shown at, so they match Minecraft's own item rendering pixel for pixel at every GUI scale. They used to be drawn at 64 pixels and resampled, which left block items with jagged edges at every GUI scale except 4.
- `NativeItemOptions.imageSize` is now nullable. The default, `null`, follows the screen: the on-screen pixels of a 16 dp icon, 16 times the GUI scale. A fixed size still applies, for icons displayed at other sizes.
- When the GUI scale changes, icons are redrawn at the new size one atlas page per frame, and keep their previous image until then.
- An icon atlas page now takes GPU memory in proportion to the GUI scale: about 1 MB at scale 2, 4 MB at scale 4 as before, and about 9 MB at scale 6.
