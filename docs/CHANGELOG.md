# CompixelUI 0.1.18

This release is for every supported Minecraft version: 1.20.1, 1.21.1, 26.1.2, 26.2 and 26.3. Mods built for the previous release keep working without changes.

- Fixed: when slots move under a pointer that stands still, for example when a mod scrolls its grid by moving slots, the hover moves to the slot now under the pointer, both in Ore's look and in [slots you draw yourself](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/inventory.md#draw-slots-yourself) (`hovered`). It used to stay on the slot hovered before the move until the pointer moved.
- Changed: when the pointer moves to another slot, only the slot it leaves and the slot it reaches are recomposed, instead of every slot on the screen. With 462 slots and the pointer reaching another slot every frame, the average frame took about 4 ms instead of about 6 ms on the test machine.
