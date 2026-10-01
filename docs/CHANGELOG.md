# CompixelUI 0.1.9

- Add `OreTooltipMode.Immediate` for hints that open on hover and close as soon as the pointer leaves their trigger, including when moving into the hint. Immediate hints use the regular bottom frame without a lock progress line; icon button hints use this mode.
- Keep `OreTooltipMode.Delayed` as the default, preserving dwell locking, the progress line and interactive nested hints. See the [tooltip guide](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/ore-ui.md#tooltips).

## CompixelUI 0.1.8

- Reduce the default menu width to 136 dp and the minimum menu row height to 22 dp while preserving text size, wrapping and keyboard navigation.
- Show menu scrollbars only when content can scroll, and reclaim their reserved space when hidden. Context menus and select dropdowns share this behavior.

## CompixelUI 0.1.7

- Add `NativeDrawing` and `MinecraftNativeDrawing` for native Minecraft drawing in arbitrary rectangular Compose layouts. Content renders at the layout's physical resolution and participates in Compose clipping, transparency and transforms.
- Share native image scheduling, publication and retirement between rectangular drawings and item icons; tooltips reuse the same image ownership and refresh policy while keeping native measurement and events.
- Replace `IconRefresh` with `NativeRefresh` in `dev.compixel.forge.drawing`. Update imports when upgrading; no compatibility alias is retained.
- Support the same rectangular drawing API on Minecraft 1.20.1, 1.21.1, 26.1.2, 26.2 and 26.3, including OpenGL and the supported Vulkan targets.

See the [native drawing guide](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/items.md#rectangular-native-drawing) for usage and sizing.
