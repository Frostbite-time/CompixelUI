package dev.compixel.development

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeInventoryScreen
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.forge.slots.ComposeMenuSlots
import dev.compixel.forge.slots.MenuSlotState
import dev.compixel.forge.slots.VanillaMenuSlotAdapter
import dev.compixel.host.gameState
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Real native container prediction, slot-render hooks, mouse translation, the slot tooltip while a button is held,
 * server acknowledgement, the screen's game state and the tasks its content posts, run at the next tick or during the
 * click that posted them, and screen release on a close request from its content.
 */
internal class InventoryAcceptanceProbe(private val done: () -> Unit) {
    private val mc
        get() = Minecraft.getInstance()

    private val player
        get() = checkNotNull(mc.player)

    private var stage = 0
    private var ticks = 0
    private var slotHooks = 0
    private var tooltips = 0
    private var heldTooltips = 0
    private var heldHooks = 0
    private var initialized: CompletableFuture<Void>? = null
    private var serverVerified: CompletableFuture<Boolean>? = null
    private var screen: AcceptanceScreen? = null

    /**
     * Shows the second hotbar slot's count as member game state and the first slot's as composable game state; after
     * the slots, its content has a press and a close button.
     */
    private inner class AcceptanceScreen :
        ComposeInventoryScreen<InventoryMenu, Unit, Nothing>(
            player.inventoryMenu,
            Component.literal("Native inventory acceptance"),
            ComposeMenuSlots(
                player.inventoryMenu,
                // Counts the tooltips the screen asks for instead of drawing them.
                object : VanillaMenuSlotAdapter(player.inventoryMenu) {
                    override fun tooltip(graphics: GuiGraphics, slot: Slot, x: Int, y: Int) {
                        if (!slot.item.isEmpty) tooltips++
                    }
                },
            ),
        ) {
        /** The member game state the content composed last. Compose thread. */
        var composed = -1
            private set

        /** The composable game state the content composed last, -1 until its first sample. Compose thread. */
        var composedFirst = -2
            private set

        /** Presses the content's tasks ran on the game thread. */
        var presses = 0
            private set

        /** Reads of the member game state on the game thread. */
        var reads = 0
            private set

        private val held by gameState {
            reads++
            player.inventory.getItem(1).count
        }

        /** Calls of [menuClosed]; the menu closes once. */
        var menuCloses = 0
            private set

        /** What the slot that draws itself showed in its latest composition. */
        var drawnSlot: MenuSlotState? = null

        /** Where the probe's pointer is while the screen draws; the hidden window's own cursor is elsewhere. */
        var pointer: Pair<Int, Int>? = null

        override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
            super.render(graphics, pointer?.first ?: mouseX, pointer?.second ?: mouseY, partialTick)

        override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

        override fun renderSlot(graphics: GuiGraphics, slot: Slot) {
            slotHooks++
            super.renderSlot(graphics, slot)
        }

        override fun menuClosed() {
            menuCloses++
        }

        @Composable
        override fun Content(state: Unit, slots: ComposeMenuSlots<InventoryMenu>) {
            val shown = held
            val first by gameState(initialValue = -1) { player.inventory.getItem(0).count }
            val firstShown = first
            SideEffect {
                composed = shown
                composedFirst = firstShown
            }
            Column(Modifier.fillMaxSize().background(Color(0xFF204060)).padding(20.dp)) {
                Row(slots.areaModifier()) {
                    slots.Slot(36)
                    // A slot that draws itself clicks and places items as a slot in Ore's look does.
                    slots.Slot(37, Modifier.size(18.dp)) { slot ->
                        SideEffect { drawnSlot = slot }
                        slot.icon?.let { MinecraftItemIcon(it, Modifier.fillMaxSize().padding(1.dp)) }
                    }
                    slots.Slot(5)
                    slots.Slot(45)
                    // Slot-sized buttons inside the container area: their clicks reach Compose alone.
                    Box(Modifier.size(18.dp).clickable { gameThread.post { presses++ } })
                    Box(Modifier.size(18.dp).clickable { requestClose() })
                }
            }
        }

        /** Posts a press as the content's press button does; call it on the Compose thread. */
        fun press() = gameThread.post { presses++ }
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
                        "The first composition did not show the screen's member game state"
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
            5 -> {
                // A button held with an empty cursor keeps the tooltip of the slot under the pointer, as a vanilla
                // container does: press on the empty slot, then move over the filled one.
                val active = checkNotNull(screen)
                val (emptyX, emptyY) = center(36)
                val (filledX, filledY) = center(37)
                active.mouseMoved(emptyX, emptyY)
                active.mouseClicked(emptyX, emptyY, SuitePlatform.MOUSE_LEFT)
                active.mouseMoved(filledX, filledY)
                active.pointer = filledX.toInt() to filledY.toInt()
                heldTooltips = tooltips
                heldHooks = slotHooks
                stage++
            }
            6 ->
                // Once a frame has been drawn with the button still held
                if (slotHooks > heldHooks) {
                    val active = checkNotNull(screen)
                    check(tooltips > heldTooltips) { "The slot tooltip was hidden while a mouse button was held" }
                    val (x, y) = center(37)
                    active.mouseReleased(x, y, SuitePlatform.MOUSE_LEFT)
                    active.pointer = null
                    check(player.containerMenu.carried.isEmpty && player.inventory.getItem(1).count == 8) {
                        "Holding a button over a slot moved its items"
                    }
                    stage++
                }
            7 ->
                // The next tick's game state shows the placed stack and the emptied first slot, which the composable
                // game state showed as -1 before its first sample; then the content posts a task.
                if (ComposeThread.call { screen!!.composed == 8 && screen!!.composedFirst == 0 }) {
                    val drawn = ComposeThread.call { screen!!.drawnSlot }
                    check(drawn != null && drawn.icon != null && drawn.amount == "8" && drawn.hovered) {
                        "The slot that draws itself showed $drawn"
                    }
                    check(ComposeThread.call { screen!!.press() }) { "The screen rejected its content's task" }
                    stage++
                }
            8 ->
                if (screen!!.presses == 1) {
                    // A click on the press button runs its task and reads the game state once before the input event
                    // returns, as a vanilla button acts, not at the next tick.
                    val active = checkNotNull(screen)
                    val reads = active.reads
                    clickButton(0)
                    check(active.presses == 2 && active.reads == reads + 1) {
                        "The click was not handled during its input event: ${active.presses} presses, " +
                            "${active.reads - reads} reads"
                    }
                    stage++
                }
            9 -> {
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
            10 ->
                if (serverVerified!!.isDone) {
                    if (serverVerified!!.join()) {
                        // The close button closes the screen before its input event returns.
                        clickButton(1)
                        check(SuitePlatform.screen !== screen) { "The close button did not close the screen at once" }
                        stage++
                    } else stage = 9 // Client packets may arrive after the queued server task.
                }
            11 ->
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
                    check(closed.presses == 2) { "The closed inventory screen ran a late task" }
                    check(closed.menuCloses == 1) { "The inventory screen reported ${closed.menuCloses} menu closes" }
                    stage++
                    done()
                }
        }
    }

    private fun click(slot: Int, button: Int) {
        val (x, y) = center(slot)
        clickAt(x, y, button)
    }

    private fun center(slot: Int): Pair<Double, Double> {
        val bounds = checkNotNull(checkNotNull(screen).inventory.bounds(slot)) { "Slot $slot is not laid out" }
        return (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2
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
