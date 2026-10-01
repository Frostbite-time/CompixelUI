# CompixelUI 0.1.7

- Add `NativeDrawing` and `MinecraftNativeDrawing` for native Minecraft drawing in arbitrary rectangular Compose layouts. Content renders at the layout's physical resolution and participates in Compose clipping, transparency and transforms.
- Share native image scheduling, publication and retirement between rectangular drawings and item icons; tooltips reuse the same image ownership and refresh policy while keeping native measurement and events.
- Replace `IconRefresh` with `NativeRefresh` in `dev.compixel.forge.drawing`. Update imports when upgrading; no compatibility alias is retained.
- Support the same rectangular drawing API on Minecraft 1.20.1, 1.21.1, 26.1.2, 26.2 and 26.3, including OpenGL and the supported Vulkan targets.

See the [native drawing guide](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/items.md#rectangular-native-drawing) for usage and sizing.
