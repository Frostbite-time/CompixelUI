# CompixelUI 0.1.10-alpha.12

- Fixed: the color editor fits short screens, such as 1080p at Minecraft's automatic GUI scale, which is only 270 units tall. A design's preview keeps its own size and scrolls when it is taller than the room beside the colors, instead of being squeezed, and the picker's saturation and brightness plane gets shorter before the color list loses its rows. See [Color schemes for mod developers](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/themes.md#for-mod-developers).
- Changed: the color editor has no bottom bar. Export sits next to the scheme list, Restore all next to Restore, and the export dialog itself says where the pack went.
- Added: `OreColorPicker(planeHeight = …)` sets the height of the saturation and brightness plane, 100 dp by default.
