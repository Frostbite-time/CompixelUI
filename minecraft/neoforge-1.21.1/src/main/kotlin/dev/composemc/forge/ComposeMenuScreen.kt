package dev.composemc.forge

import androidx.compose.runtime.Composable
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.MenuAccess
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Compose screen for a server-backed menu with no inventory slots.
 * Keeps vanilla MenuAccess, close packets and client removal semantics. Use
 * ComposeInventoryScreen for menus with native inventory slots.
 */
open class ComposeMenuScreen<M : AbstractContainerMenu>(
    protected val container: M,
    title: Component,
    guiUnitsPerDp: Float = 1f,
    minimumUiDensity: Float = 1f,
    content: @Composable () -> Unit,
) : ComposeScreen(title, guiUnitsPerDp = guiUnitsPerDp, minimumUiDensity = minimumUiDensity, content = content), MenuAccess<M> {
    private var menuRemoved = false

    init { require(container.slots.isEmpty()) { "Compose menu screens currently require a menu without slots" } }

    override fun getMenu(): M = container

    final override fun tick() {
        super.tick()
        val player = Minecraft.getInstance().player
        if (player == null || !player.isAlive || player.isRemoved) onClose()
        else containerTick()
    }

    protected open fun containerTick() {}

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!hasTextInputFocus && Minecraft.getInstance().options.keyInventory.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun onClose() {
        Minecraft.getInstance().player?.closeContainer()
        super.onClose()
    }

    override fun removed() {
        try { super.removed() } finally {
            if (!menuRemoved && Minecraft.getInstance().player?.containerMenu !== container) {
                menuRemoved = true
                Minecraft.getInstance().player?.let(container::removed)
            }
        }
    }
}
