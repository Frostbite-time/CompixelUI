package dev.compixel.development

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.compixel.forge.ComposeInventoryScreen
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Real native container prediction, slot-render hooks, mouse translation, server acknowledgement and screen release.
 */
internal class InventoryAcceptanceProbe(private val done: () -> Unit) {
    private val mc
        get() = Minecraft.getInstance()

    private val player
        get() = checkNotNull(mc.player)

    private var stage = 0
    private var ticks = 0
    private var slotHooks = 0
    private var initialized: CompletableFuture<Void>? = null
    private var serverVerified: CompletableFuture<Boolean>? = null
    private var screen: ComposeInventoryScreen<InventoryMenu>? = null

    fun tick() {
        check(++ticks < 600) { "Inventory acceptance timed out at stage $stage" }
        when (stage) {
            0 -> {
                initialized =
                    checkNotNull(mc.singleplayerServer)
                        .submit(
                            Runnable {
                                val remote = checkNotNull(mc.singleplayerServer).playerList.players.first()
                                remote.inventory.setItem(0, ItemStack(Items.DIAMOND, 8))
                                remote.inventory.setItem(1, ItemStack.EMPTY)
                                remote.inventoryMenu.broadcastChanges()
                            }
                        )
                stage++
            }
            1 ->
                if (initialized!!.isDone && player.inventory.getItem(0).count == 8) {
                    initialized!!.join()
                    screen =
                        object :
                            ComposeInventoryScreen<InventoryMenu>(
                                player.inventoryMenu,
                                Component.literal("Native inventory acceptance"),
                                content = { slots ->
                                    Column(Modifier.fillMaxSize().background(Color(0xFF204060)).padding(20.dp)) {
                                        Row(slots.areaModifier()) {
                                            slots.Slot(36)
                                            slots.Slot(37)
                                            slots.Slot(5)
                                            slots.Slot(45)
                                        }
                                    }
                                },
                            ) {
                            override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

                            override fun renderSlot(graphics: GuiGraphics, slot: Slot) {
                                slotHooks++
                                super.renderSlot(graphics, slot)
                            }
                        }
                    SuitePlatform.setScreen(screen)
                    stage++
                }
            2 ->
                // Layout already ran during init; the hooks run in the same pass as the first rendered frame.
                if (screen!!.inventory.bounds(36) != null && screen!!.rendererStatistics.renderedFrames > 0) {
                    check(slotHooks > 0) { "The native per-slot render hook was not dispatched" }
                    click(36, SuitePlatform.MOUSE_LEFT)
                    check(player.containerMenu.carried.count == 8) { "Left-click did not pick up the stack" }
                    stage++
                }
            3 -> {
                click(37, SuitePlatform.MOUSE_RIGHT)
                check(player.containerMenu.carried.count == 7 && player.inventory.getItem(1).count == 1) {
                    "Right-click did not place one item"
                }
                stage++
            }
            4 -> {
                click(37, SuitePlatform.MOUSE_LEFT)
                check(player.containerMenu.carried.isEmpty && player.inventory.getItem(1).count == 8) {
                    "Left-click did not place the rest"
                }
                stage++
            }
            5 -> {
                serverVerified =
                    checkNotNull(mc.singleplayerServer)
                        .submit(
                            java.util.function.Supplier {
                                val remote = checkNotNull(mc.singleplayerServer).playerList.players.first()
                                remote.inventory.getItem(0).isEmpty &&
                                    remote.inventory.getItem(1).count == 8 &&
                                    remote.containerMenu.carried.isEmpty
                            }
                        )
                stage++
            }
            6 ->
                if (serverVerified!!.isDone) {
                    if (serverVerified!!.join()) {
                        val active = checkNotNull(screen)
                        active.onClose()
                        check(
                            active.rendererStatistics.liveSurfaces == 0 &&
                                active.rendererStatistics.liveNativeImages == 0 &&
                                active.rendererStatistics.strandedNativeImages == 0
                        ) {
                            "The inventory screen kept its renderer after closing: ${active.rendererStatistics}"
                        }
                        stage++
                        done()
                    } else stage = 5 // Client packets may arrive after the queued server task.
                }
        }
    }

    private fun click(slot: Int, button: Int) {
        val active = checkNotNull(screen)
        val bounds = checkNotNull(active.inventory.bounds(slot)) { "Slot $slot is not laid out" }
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        active.mouseMoved(x, y)
        active.mouseClicked(x, y, button)
        active.mouseReleased(x, y, button)
    }
}
