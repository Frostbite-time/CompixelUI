package dev.composemc.neoforge

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.model.ItemOverrides
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import java.util.IdentityHashMap

/** Inspects only newly prepared immutable item handles; discarded on resource reload. */
internal class NativeIconAnimation {
    private val sprites = IdentityHashMap<SpriteContents, Boolean>()
    private val random = RandomSource.create(42)
    fun resolve(icon: ItemIcon): IconRefresh {
        if (icon.refresh !== IconRefresh.AUTO) return icon.refresh
        if (icon.stack.isEmpty) return IconRefresh.GAME_TICK // An opaque custom drawing has no model to inspect.
        val mc = Minecraft.getInstance()
        val stack = icon.stack
        val base = mc.itemRenderer.itemModelShaper.getItemModel(stack)
        if (stack.hasFoil() || base.isCustomRenderer) return IconRefresh.FRAME
        if (base.overrides !== ItemOverrides.EMPTY) return IconRefresh.GAME_TICK
        val model = mc.itemRenderer.getModel(stack, mc.level, null, 0)
        for (pass in model.getRenderPasses(stack, true)) {
            if (pass.isCustomRenderer) return IconRefresh.FRAME
            if (pass.overrides !== ItemOverrides.EMPTY || animated(pass.particleIcon)) return IconRefresh.GAME_TICK
            for (face in Direction.entries + listOf(null)) {
                random.setSeed(42)
                if (pass.getQuads(null, face, random).any { animated(it.sprite) }) return IconRefresh.GAME_TICK
            }
        }
        return IconRefresh.STATIC
    }
    private fun animated(sprite: TextureAtlasSprite): Boolean {
        val contents = sprite.contents()
        return sprites.getOrPut(contents) { contents.uniqueFrames.use { it.limit(2).count() > 1 } }
    }
    fun clear() = sprites.clear()
}
