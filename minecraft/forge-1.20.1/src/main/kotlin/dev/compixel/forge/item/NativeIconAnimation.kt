package dev.compixel.forge.item

import dev.compixel.forge.drawing.NativeRefresh
import java.util.IdentityHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack

/** Resolves AUTO for newly cached immutable item handles and reports their appearance; reset on resource reload. */
internal class NativeIconAnimation {
    private val sprites = IdentityHashMap<SpriteContents, Boolean>()
    private val random = RandomSource.create(42)

    fun resolve(icon: ItemIcon): NativeRefresh {
        if (icon.refresh !== NativeRefresh.AUTO) return icon.refresh
        if (icon.stack.isEmpty) return NativeRefresh.GAME_TICK // An opaque custom drawing has no model to inspect.
        val stack = icon.stack
        // Glint and custom renderers animate continuously; like animated sprites, they redraw once per game tick.
        if (
            stack.hasFoil() || Minecraft.getInstance().itemRenderer.itemModelShaper.getItemModel(stack).isCustomRenderer
        )
            return NativeRefresh.GAME_TICK
        // Model overrides, such as compass and clock angles, are followed through appearance().
        for (pass in model(stack).getRenderPasses(stack, true)) {
            if (pass.isCustomRenderer || animated(pass.particleIcon)) return NativeRefresh.GAME_TICK
            for (face in Direction.entries + listOf(null)) {
                random.setSeed(42)
                if (pass.getQuads(null, face, random).any { animated(it.sprite) }) return NativeRefresh.GAME_TICK
            }
        }
        return NativeRefresh.ON_CHANGE
    }

    /** What a page draws for an ON_CHANGE icon: its override-resolved model and cooldown overlay rows. */
    fun appearance(icon: ItemIcon): Any {
        val mc = Minecraft.getInstance()
        val cooldown = mc.player?.cooldowns?.getCooldownPercent(icon.stack.item, mc.frameTime) ?: 0f
        return listOf(model(icon.stack), Mth.ceil(16f * cooldown))
    }

    // Resolved as the page draws it: held by the local player, in the current level, with seed 0.
    private fun model(stack: ItemStack) =
        Minecraft.getInstance().let { it.itemRenderer.getModel(stack, it.level, it.player, 0) }

    private fun animated(sprite: TextureAtlasSprite): Boolean {
        val contents = sprite.contents()
        return sprites.getOrPut(contents) { contents.uniqueFrames.use { it.limit(2).count() > 1 } }
    }

    fun clear() = sprites.clear()
}
