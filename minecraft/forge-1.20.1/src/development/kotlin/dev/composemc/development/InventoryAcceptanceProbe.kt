package dev.composemc.development

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.composemc.forge.ForgeComposeInventoryScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.CompletableFuture

/** Real native container prediction, mouse translation and integrated-server acknowledgement. */
internal class InventoryAcceptanceProbe(private val done: () -> Unit) {
    private val mc get() = Minecraft.getInstance()
    private val player get() = checkNotNull(mc.player)
    private var stage = 0
    private var ticks = 0
    private var renderedSlotHooks = 0
    private var initialized: CompletableFuture<Void>? = null
    private var serverVerified: CompletableFuture<Boolean>? = null
    private var screen: ForgeComposeInventoryScreen<net.minecraft.world.inventory.InventoryMenu>? = null

    fun tick() {
        check(++ticks < 600) { "Inventory acceptance timed out at stage $stage" }
        when (stage) {
            0 -> {
                initialized = checkNotNull(mc.singleplayerServer).submit(Runnable {
                    val remote = checkNotNull(mc.singleplayerServer).playerList.players.first()
                    remote.inventory.setItem(0, ItemStack(Items.DIAMOND, 8))
                    remote.inventory.setItem(1, ItemStack.EMPTY)
                    remote.inventoryMenu.broadcastChanges()
                })
                stage++
            }
            1 -> if (initialized!!.isDone && player.inventory.getItem(0).count == 8) {
                initialized!!.join()
                screen = object : ForgeComposeInventoryScreen<net.minecraft.world.inventory.InventoryMenu>(
                    player.inventoryMenu, Component.literal("Native inventory acceptance"), content = { slots ->
                        Column(Modifier.fillMaxSize().background(Color(0xFF204060)).padding(20.dp)) {
                            Row(slots.areaModifier()) { slots.Slot(36); slots.Slot(37); slots.Slot(5); slots.Slot(45) }
                        }
                    }) {
                        override fun isUiWindowFocused() = true
                        override fun renderSlot(graphics: net.minecraft.client.gui.GuiGraphics, slot: net.minecraft.world.inventory.Slot) {
                            renderedSlotHooks++
                            super.renderSlot(graphics, slot)
                        }
                    }
                mc.setScreen(screen)
                stage++
            }
            2 -> if (screen!!.inventory.bounds(36) != null) {
                check(renderedSlotHooks > 0) { "Native slot-render override was not dispatched" }
                click(36, 0)
                check(player.containerMenu.carried.count == 8) { "Left-click did not pick up the stack" }
                stage++
            }
            3 -> {
                click(37, 1)
                check(player.containerMenu.carried.count == 7 && player.inventory.getItem(1).count == 1) {
                    "Right-click did not place one item"
                }
                stage++
            }
            4 -> {
                click(37, 0)
                check(player.containerMenu.carried.isEmpty && player.inventory.getItem(1).count == 8)
                stage++
            }
            5 -> {
                serverVerified = checkNotNull(mc.singleplayerServer).submit(java.util.function.Supplier {
                    val remote = checkNotNull(mc.singleplayerServer).playerList.players.first()
                    remote.inventory.getItem(0).isEmpty && remote.inventory.getItem(1).count == 8 && remote.containerMenu.carried.isEmpty
                })
                stage++
            }
            6 -> if (serverVerified!!.isDone) {
                if (serverVerified!!.join()) { stage++; done() }
                else stage = 5 // Client packets may arrive after the queued server task.
            }
        }
    }

    private fun click(slot: Int, button: Int) {
        val active = checkNotNull(screen)
        val bounds = checkNotNull(active.inventory.bounds(slot))
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        active.mouseMoved(x, y)
        active.mouseClicked(x, y, button)
        active.mouseReleased(x, y, button)
    }
}
