package dev.composemc.forge.item

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/** Resolves AUTO for newly cached immutable item handles and reports their appearance. */
internal class NativeIconAnimation {
    fun resolve(icon: ItemIcon): IconRefresh {
        if (icon.refresh !== IconRefresh.AUTO) return icon.refresh
        if (icon.stack.isEmpty) return IconRefresh.GAME_TICK // An opaque custom drawing has no model to inspect.
        // Glint also marks the render state animated, so it is checked first.
        if (icon.stack.hasFoil()) return IconRefresh.FRAME
        return if (resolved(icon.stack).isAnimated) IconRefresh.GAME_TICK else IconRefresh.ON_CHANGE
    }
    /**
     * What a page draws for an ON_CHANGE icon: its model identity, which records every property-selected
     * model and tint just as Minecraft's own GUI item atlas keys it, and the cooldown overlay rows.
     */
    fun appearance(icon: ItemIcon): Any {
        val minecraft = Minecraft.getInstance()
        val cooldown = minecraft.player?.cooldowns
            ?.getCooldownPercent(icon.stack, minecraft.deltaTracker.getGameTimeDeltaPartialTick(true)) ?: 0f
        return listOf(resolved(icon.stack).modelIdentity, Mth.ceil(16f * cooldown))
    }
    // Resolved as the page draws it: held by the local player, in the current level, with seed 0.
    private fun resolved(stack: ItemStack) = TrackingItemStackRenderState().also {
        val minecraft = Minecraft.getInstance()
        minecraft.itemModelResolver.updateForTopItem(it, stack, ItemDisplayContext.GUI, minecraft.level, minecraft.player, 0)
    }
}
