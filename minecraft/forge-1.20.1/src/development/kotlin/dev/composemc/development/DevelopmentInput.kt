package dev.composemc.development

import net.minecraft.client.gui.screens.Screen

/** The shared suites scroll with the four-argument callback that later versions provide. */
internal fun Screen.mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
    require(horizontal == 0.0) { "Minecraft 1.20.1 has no horizontal scroll callback" }
    return mouseScrolled(x, y, vertical)
}
