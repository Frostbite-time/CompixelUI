# CompixelUI 0.1.10-alpha.15

- Fixed: dragging through the dark part of `OreColorPicker`'s plane no longer shakes the pointer sideways, and arrow-key steps no longer drift. The picker keeps the hue, saturation and brightness it chose instead of reading them back from the 8-bit color, including while a host returns the value a few frames later; a color it did not choose, such as a typed hex value or a restored default, still moves the pointer to that color.
