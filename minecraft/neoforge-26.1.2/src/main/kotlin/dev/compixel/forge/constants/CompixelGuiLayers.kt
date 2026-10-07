package dev.compixel.forge.constants

import net.minecraft.resources.Identifier

/** IDs of the HUD layers that CompixelUI registers, for ordering your own layers against them. */
object CompixelGuiLayers {
    /**
     * Closed screens play their exit here, above every other HUD layer. Register yours below it to draw beneath closing
     * screens, for example an effect that lasts until their exit has finished.
     */
    @JvmField val SCREEN_EXITS: Identifier = Identifier.fromNamespaceAndPath("compixel", "screen_exits")
}
