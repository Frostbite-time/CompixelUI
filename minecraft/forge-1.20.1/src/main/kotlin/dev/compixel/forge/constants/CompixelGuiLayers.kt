package dev.compixel.forge.constants

import net.minecraft.resources.ResourceLocation

/** IDs of the HUD overlays that CompixelUI registers, for ordering your own overlays against them. */
object CompixelGuiLayers {
    /**
     * Closed screens play their exit here, above every other HUD overlay. Register yours below it to draw beneath
     * closing screens, for example an effect that lasts until their exit has finished.
     */
    @JvmField val SCREEN_EXITS: ResourceLocation = ResourceLocation("compixel", "screen_exits")
}
