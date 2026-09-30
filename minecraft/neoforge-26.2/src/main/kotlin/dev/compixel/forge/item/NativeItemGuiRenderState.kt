package dev.compixel.forge.item

import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState

/**
 * Native icon pages rasterize ordinary items at their demanded pixel size, rather than resampling GuiRenderer's
 * integer-scale cache. Oversized models retain Minecraft's dedicated renderer, including its overflow behavior.
 */
internal class NativeItemGuiRenderState : GuiRenderState() {
    private var preparedItems = false
    private val remainingModels = HashSet<Any>()

    override fun getItemModelIdentities(): Set<Any> =
        if (preparedItems) remainingModels else super.getItemModelIdentities()

    override fun forEachItem(consumer: java.util.function.Consumer<GuiItemRenderState>) {
        super.forEachItem { item ->
            if (!preparedItems || item.oversizedItemBounds() != null) consumer.accept(item)
        }
    }

    fun prepareItems(
        imageSize: Int,
        prepare: (Int, Set<Any>) -> (GuiItemRenderState) -> BlitRenderState?,
    ) {
        check(!preparedItems)
        require(imageSize in dev.compixel.bridge.NativeIconAtlas.IMAGE_SIZES)
        val models = HashSet<Any>()
        super.forEachItem { item ->
            val identity = item.itemStackRenderState().modelIdentity
            if (item.oversizedItemBounds() == null) models += identity else remainingModels += identity
        }
        if (models.isNotEmpty()) {
            val blit = prepare(imageSize, models)
            // forEachItem selects the original item's layer before this blit is appended.
            super.forEachItem { item ->
                if (item.oversizedItemBounds() == null) blit(item)?.let { addBlitToCurrentLayer(it) }
            }
        }
        preparedItems = true
    }

    override fun reset() {
        super.reset()
        preparedItems = false
        remainingModels.clear()
    }
}
