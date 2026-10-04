package dev.compixel.development

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeInventoryScreen
import dev.compixel.forge.slots.ComposeMenuSlots
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Real native container prediction, slot-render hooks, mouse translation, server acknowledgement, the screen's state
 * and actions, handled at the next tick or during the click that sent them, and screen release on a close request from
 * its content.
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
    private var screen: AcceptanceScreen? = null

    /**
     * Shows the second hotbar slot's count as its state; after the slots, its content has a press and a close button.
     */
    private inner class AcceptanceScreen :
        ComposeInventoryScreen<InventoryMenu, Int, Unit>(
            player.inventoryMenu,
            Component.literal("Native inventory acceptance"),
        ) {
        /** The latest state the content composed. Compose thread. */
        var composed = -1
            private set

        /** Presses handled on the game thread. */
        var presses = 0
            private set

        /** Snapshots taken on the game thread. */
        var snapshots = 0
            private set

        /** Calls of [menuClosed]; the menu closes once. */
        var menuCloses = 0
            private set

        override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

        override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {
            slotHooks++
            super.extractSlot(graphics, slot, mouseX, mouseY)
        }

        override fun snapshot(): Int {
            snapshots++
            return player.inventory.getItem(1).count
        }

        override fun handle(action: Unit) {
            presses++
        }

        override fun menuClosed() {
            menuCloses++
        }

        @Composable
        override fun Content(state: Int, slots: ComposeMenuSlots<InventoryMenu>) {
            SideEffect { composed = state }
            Column(Modifier.fillMaxSize().background(Color(0xFF204060)).padding(20.dp)) {
                Row(slots.areaModifier()) {
                    slots.Slot(36)
                    slots.Slot(37)
                    slots.Slot(5)
                    slots.Slot(45)
                    // Slot-sized buttons inside the container area: their clicks reach Compose alone.
                    Box(Modifier.size(18.dp).clickable { send(Unit) })
                    Box(Modifier.size(18.dp).clickable { requestClose() })
                }
            }
        }

        /** Sends a press as the content's press button does; call it on the Compose thread. */
        fun press() = send(Unit)
    }

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
                    screen = AcceptanceScreen()
                    SuitePlatform.setScreen(screen)
                    stage++
                }
            2 ->
                // Layout already ran during init; the hooks run in the same pass as the first rendered frame.
                if (screen!!.inventory.bounds(36) != null && screen!!.rendererStatistics.renderedFrames > 0) {
                    check(slotHooks > 0) { "The native per-slot render hook was not dispatched" }
                    check(ComposeThread.call { screen!!.composed } == 0) {
                        "The first composition did not show the screen's opening snapshot"
                    }
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
            5 ->
                // The next tick's snapshot shows the placed stack, and the content sends an action back.
                if (ComposeThread.call { screen!!.composed } == 8) {
                    check(ComposeThread.call { screen!!.press() }) { "The screen rejected its content's action" }
                    stage++
                }
            6 ->
                if (screen!!.presses == 1) {
                    // A click on the press button runs its action and takes one snapshot before the input event
                    // returns, as a vanilla button acts, not at the next tick.
                    val active = checkNotNull(screen)
                    val snapshots = active.snapshots
                    clickButton(0)
                    check(active.presses == 2 && active.snapshots == snapshots + 1) {
                        "The click was not handled during its input event: ${active.presses} presses, " +
                            "${active.snapshots - snapshots} snapshots"
                    }
                    stage++
                }
            7 -> {
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
            8 ->
                if (serverVerified!!.isDone) {
                    if (serverVerified!!.join()) {
                        // The close button closes the screen before its input event returns.
                        clickButton(1)
                        check(SuitePlatform.screen !== screen) { "The close button did not close the screen at once" }
                        stage++
                    } else stage = 7 // Client packets may arrive after the queued server task.
                }
            9 ->
                if (SuitePlatform.screen !== screen) {
                    val closed = checkNotNull(screen)
                    check(
                        closed.rendererStatistics.liveSurfaces == 0 &&
                            closed.rendererStatistics.liveNativeImages == 0 &&
                            closed.rendererStatistics.strandedNativeImages == 0
                    ) {
                        "The inventory screen kept its renderer after closing: ${closed.rendererStatistics}"
                    }
                    check(!closed.contentState.isOpen && !ComposeThread.call { closed.press() }) {
                        "The closed inventory screen kept its state"
                    }
                    check(closed.presses == 2) { "The closed inventory screen handled a late action" }
                    check(closed.menuCloses == 1) { "The inventory screen reported ${closed.menuCloses} menu closes" }
                    stage++
                    done()
                }
        }
    }

    private fun click(slot: Int, button: Int) {
        val bounds = checkNotNull(checkNotNull(screen).inventory.bounds(slot)) { "Slot $slot is not laid out" }
        clickAt((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2, button)
    }

    /** Clicks the content's button [index]; the buttons follow the last slot and have its size. */
    private fun clickButton(index: Int) {
        val slot = checkNotNull(checkNotNull(screen).inventory.bounds(45)) { "Slot 45 is not laid out" }
        val width = slot.right - slot.left
        clickAt(slot.right + width * (index + 0.5), (slot.top + slot.bottom) / 2, SuitePlatform.MOUSE_LEFT)
    }

    private fun clickAt(x: Double, y: Double, button: Int) {
        val active = checkNotNull(screen)
        active.mouseMoved(x, y)
        active.mouseClicked(x, y, button)
        active.mouseReleased(x, y, button)
    }
}
