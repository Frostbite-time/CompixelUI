package dev.compixel.development

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeMenuScreen
import dev.compixel.sync.action.ActionStatus
import dev.compixel.sync.session.SyncStatus
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** What the screen's content shows of the synchronized menu. */
internal data class SyncAcceptanceView(val ready: Boolean, val counter: Int, val emeralds: Int)

/** Its content is one button over the whole screen, which requests the replacement text through the screen's state. */
internal class SyncAcceptanceScreen(
    menu: SyncAcceptanceMenu,
    @Suppress("UNUSED_PARAMETER") inventory: Inventory,
    title: Component,
) : ComposeMenuScreen<SyncAcceptanceMenu, SyncAcceptanceView, String>(menu, title), ShowsTransition {
    /** The latest state the content composed. Compose thread. */
    var composed: SyncAcceptanceView? = null
        private set

    /** Actions handled on the game thread. */
    var requests = 0
        private set

    /** Calls of [menuClosed]; the menu closes once. */
    var menuCloses = 0
        private set

    override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    override fun snapshot() =
        SyncAcceptanceView(
            container.sync.hasSnapshot(),
            container.counter,
            if (container.item.item == Items.EMERALD) container.item.count else 0,
        )

    override fun handle(action: String) {
        requests++
        check(container.sync.request(SyncAcceptanceMenu.REPLACE, action).queued()) {
            "The content's action was not queued"
        }
    }

    override fun menuClosed() {
        menuCloses++
    }

    /** Screens created while [transitions] is on enter and exit through a ScreenTransition. */
    private val animated = transitions

    @Volatile override var shown: TransitionShown? = null

    @Composable
    override fun Content(state: SyncAcceptanceView) {
        SideEffect { composed = state }
        if (animated) ReportedTransition { Button() } else Button()
    }

    @Composable private fun Button() = Box(Modifier.fillMaxSize().clickable { send(SyncAcceptanceMenu.REPLACEMENT) })

    /** Sends [text] as the content's button does; call it on the Compose thread. */
    fun replace(text: String) = send(text)

    /** What a close button in the content does; call it on the Compose thread. */
    fun closeFromContent() = requestClose()

    companion object {
        /** Read when a screen is created; TransitionAcceptance turns it on for the screens it opens. */
        @Volatile var transitions = false
    }
}

/**
 * A real server-opened menu: bounded multi-batch snapshot, fragmented action round trip from the screen's content, a
 * native value through one shared codec in both directions, and screen release on a close request from the content.
 */
internal class MenuSyncAcceptanceProbe(private val done: () -> Unit) {
    private val mc
        get() = Minecraft.getInstance()

    private var ticks = 0
    private var stage = 0
    private var screen: SyncAcceptanceScreen? = null

    fun tick() {
        check(++ticks < 600) { "Menu transport acceptance timed out at stage $stage" }
        when (stage) {
            // The server closes whatever menu is open when any close packet arrives, including the one the
            // previous inventory screen just sent. Open only after that packet has certainly been handled.
            0 ->
                if (ticks >= 20) {
                    val server = checkNotNull(mc.singleplayerServer)
                    server.execute {
                        val player = server.playerList.players.first()
                        player.openMenu(
                            SimpleMenuProvider(
                                { id, inventory, _ -> SyncAcceptanceMenu(id, inventory) },
                                Component.literal("Menu transport acceptance"),
                            )
                        )
                    }
                    stage++
                }
            1 ->
                (mc.player?.containerMenu as? SyncAcceptanceMenu)?.let { menu ->
                    check(menu.sync.status() != SyncStatus.FAILED) {
                        "Menu sync failed: ${menu.sync.statistics()}"
                    }
                    if (menu.sync.hasSnapshot()) {
                        screen =
                            SuitePlatform.screen as? SyncAcceptanceScreen
                                ?: error("The synchronized menu did not open its screen")
                        check(menu.counter == 7 && menu.text == SyncAcceptanceMenu.INITIAL) {
                            "The initial snapshot is wrong"
                        }
                        check(menu.item.item == Items.DIAMOND && menu.item.count == 3) {
                            "The native value in the initial snapshot is wrong: ${menu.item}"
                        }
                        check(menu.sync.statistics().largestBatch() <= menu.sync.options().state().batchBytes()) {
                            "A state batch exceeded its bound"
                        }
                        stage++
                    }
                }
            2 -> {
                val active = checkNotNull(screen)
                // The screen's snapshot of the menu's initial state has reached the content.
                if (ComposeThread.call { active.composed } == SyncAcceptanceView(true, 7, 0)) {
                    // A click on the content's button runs its action before the input event returns, as a vanilla
                    // button's would, not at the next tick.
                    val x = active.width / 2.0
                    val y = active.height / 2.0
                    active.mouseMoved(x, y)
                    active.mouseClicked(x, y, SuitePlatform.MOUSE_LEFT)
                    active.mouseReleased(x, y, SuitePlatform.MOUSE_LEFT)
                    check(active.requests == 1) {
                        "The click's action ran ${active.requests} times before its input event returned"
                    }
                    stage++
                }
            }
            3 -> {
                val menu = mc.player!!.containerMenu as SyncAcceptanceMenu
                check(menu.sync.status() != SyncStatus.FAILED) { "Menu sync failed: ${menu.sync.statistics()}" }
                val result = menu.sync.lastActionResult()
                if (
                    result != null &&
                        menu.counter == 42 &&
                        menu.text == SyncAcceptanceMenu.REPLACEMENT &&
                        ComposeThread.call { checkNotNull(screen).composed } == SyncAcceptanceView(true, 42, 0)
                ) {
                    check(result.status() == ActionStatus.APPLIED) {
                        "The action was not applied: ${result.status()}"
                    }
                    check(menu.sync.statistics().largestBatch() <= menu.sync.options().state().batchBytes()) {
                        "A state batch exceeded its bound"
                    }
                    // The client encodes this native value and the server decodes it with the same shared codec.
                    check(menu.sync.request(SyncAcceptanceMenu.GIVE, ItemStack(Items.EMERALD, 5)).queued()) {
                        "The native-value action was not queued"
                    }
                    stage++
                }
            }
            4 -> {
                val menu = mc.player!!.containerMenu as SyncAcceptanceMenu
                check(menu.sync.status() != SyncStatus.FAILED) { "Menu sync failed: ${menu.sync.statistics()}" }
                val result = menu.sync.lastActionResult()
                val active = checkNotNull(screen)
                if (
                    result != null &&
                        result.action() == "give" &&
                        menu.item.item == Items.EMERALD &&
                        menu.item.count == 5 &&
                        ComposeThread.call { active.composed } == SyncAcceptanceView(true, 42, 5)
                ) {
                    check(result.status() == ActionStatus.APPLIED) {
                        "The native-value action was not applied: ${result.status()}"
                    }
                    // The screen closes the menu on its next tick.
                    ComposeThread.call { active.closeFromContent() }
                    stage++
                }
            }
            5 ->
                if (mc.player?.containerMenu === mc.player?.inventoryMenu) {
                    val closed = checkNotNull(screen)
                    check(closed.session == null && closed.rendererStatistics.liveSurfaces == 0) {
                        "The menu screen kept its renderer after closing"
                    }
                    check(!closed.contentState.isOpen && !ComposeThread.call { closed.replace("late") }) {
                        "The closed menu screen kept its state"
                    }
                    check(closed.menuCloses == 1) { "The menu screen reported ${closed.menuCloses} menu closes" }
                    stage++
                    done()
                }
        }
    }
}
