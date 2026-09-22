package dev.composemc.development

import net.minecraft.client.gui.screens.Screen

internal fun Screen.charTyped(character: Char): Boolean = charTyped(character, 0)
internal fun Screen.keyPressed(key: Int): Boolean = keyPressed(key, 0, 0)
internal fun Screen.keyReleased(key: Int): Boolean = keyReleased(key, 0, 0)
internal fun Screen.mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
    require(horizontal == 0.0) { "Minecraft 1.20.1 has no horizontal scroll callback" }
    return mouseScrolled(x, y, vertical)
}
