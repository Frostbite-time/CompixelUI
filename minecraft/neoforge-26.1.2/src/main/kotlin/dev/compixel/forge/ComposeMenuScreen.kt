package dev.compixel.forge

import com.mojang.blaze3d.platform.InputConstants
import dev.compixel.forge.drawing.NativeDrawingOptions
import dev.compixel.forge.item.NativeItemOptions
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.theme.OreDesign
import dev.compixel.ui.theme.ThemeId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.MenuAccess
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Compose screen for a server-backed menu with no inventory slots, showing the menu's state as [ComposeScreen] does.
 * Keeps vanilla MenuAccess, close packets and client removal semantics. Use ComposeInventoryScreen for menus with
 * native inventory slots.
 */
abstract class ComposeMenuScreen<M : AbstractContainerMenu, S, A>(
    protected val container: M,
    title: Component,
    guiUnitsPerDp: Float = 1f,
    minimumUiDensity: Float = 1f,
    theme: ThemeId = ThemeId.Default,
    nativeItemOptions: NativeItemOptions = NativeItemOptions(),
    nativeDrawingOptions: NativeDrawingOptions = NativeDrawingOptions(),
    design: UiDesign = OreDesign,
) :
    ComposeScreen<S, A>(
        title,
        guiUnitsPerDp = guiUnitsPerDp,
        minimumUiDensity = minimumUiDensity,
        nativeItemOptions = nativeItemOptions,
        nativeDrawingOptions = nativeDrawingOptions,
        theme = theme,
        design = design,
    ),
    MenuAccess<M> {
    private var menuRemoved = false

    init {
        require(container.slots.isEmpty()) { "Compose menu screens currently require a menu without slots" }
    }

    override fun getMenu(): M = container

    final override fun tick() {
        val player = Minecraft.getInstance().player
        if (player == null || !player.isAlive || player.isRemoved) onClose()
        else {
            containerTick()
            super.tick() // The content's state and close requests follow the container tick.
        }
    }

    protected open fun containerTick() {}

    /**
     * Runs once on the game thread after the menu has closed and the screen has let go of it, for example to save what
     * the player entered. A screen that only covers this one, such as a recipe viewer, does not close the menu: this
     * screen keeps its content as it was and shows it again.
     */
    protected open fun menuClosed() {}

    override fun keyPressed(event: KeyEvent): Boolean {
        if (
            !hasTextInputFocus &&
                Minecraft.getInstance().options.keyInventory.isActiveAndMatches(InputConstants.getKey(event))
        ) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        Minecraft.getInstance().player?.closeContainer()
        super.onClose()
    }

    override fun removed() {
        try {
            super.removed()
        } finally {
            if (!coveredByAnotherScreen()) endMenu()
        }
    }

    // The menu stays open while a recipe viewer or another screen covers this one.
    override fun coveredByAnotherScreen() = Minecraft.getInstance().player?.containerMenu === container

    override fun closeCovered() {
        try {
            super.closeCovered()
        } finally {
            endMenu()
        }
    }

    private fun endMenu() {
        if (menuRemoved) return
        menuRemoved = true
        Minecraft.getInstance().player?.let(container::removed)
        menuClosed()
    }
}
