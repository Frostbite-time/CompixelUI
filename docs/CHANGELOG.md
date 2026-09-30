# CompixelUI 0.1.5

- Each item icon is drawn at the pixels it is laid out with, so icons of any size, not only 16 dp ones, are shown pixel for pixel. An icon shown at two sizes is drawn at both.
- The default `NativeItemOptions.imageSize`, `null`, now follows each icon's size instead of the size of a 16 dp icon. A fixed size still draws every icon once and resamples it on screen.
- When an icon's size keeps changing, as in an animation, it shows its nearest drawn size until the new size has held for two frames.
- On Minecraft 26.1.2, 26.2 and 26.3, item icons no longer depend on the window size, which scaled them slightly when the window was not a multiple of the GUI scale. Icons larger than 16 dp are drawn at no less than their own resolution instead of enlarged from Minecraft's 16 dp item cache.
- An atlas page holds icons of one size. Icons shown at 16 dp take the same GPU memory as before; larger icons take more, with the square of their size.
