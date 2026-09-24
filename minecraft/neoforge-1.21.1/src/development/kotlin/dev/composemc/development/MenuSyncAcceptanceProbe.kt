package dev.composemc.development

import dev.composemc.neoforge.NeoForgeComposeMenuScreen
import dev.composemc.neoforge.sync.MenuSync
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory

internal class SyncAcceptanceScreen(menu: SyncAcceptanceMenu, @Suppress("UNUSED_PARAMETER") inventory: Inventory, title: Component) :
    NeoForgeComposeMenuScreen<SyncAcceptanceMenu>(menu, title, content = {}) {
    override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())
}

/** A real server-opened menu: bounded multi-batch snapshot, fragmented action round trip and screen release. */
internal class MenuSyncAcceptanceProbe(private val done: () -> Unit) {
    private val mc get() = Minecraft.getInstance()
    private var ticks = 0
    private var stage = 0
    private var screen: SyncAcceptanceScreen? = null

    fun tick() {
        check(++ticks < 600) { "Menu transport acceptance timed out at stage $stage" }
        when (stage) {
            // The server closes whatever menu is open when any close packet arrives, including the one the
            // previous inventory screen just sent. Open only after that packet has certainly been handled.
            0 -> if (ticks >= 20) {
                val server = checkNotNull(mc.singleplayerServer)
                server.execute {
                    val player = server.playerList.players.first()
                    player.openMenu(SimpleMenuProvider({ id, inventory, _ -> SyncAcceptanceMenu(id, inventory) },
                        Component.literal("Menu transport acceptance")))
                }
                stage++
            }
            1 -> (mc.player?.containerMenu as? SyncAcceptanceMenu)?.let { menu ->
                check(menu.sync.status() != MenuSync.Status.FAILED) { "Menu sync failed: ${menu.sync.statistics()}" }
                if (menu.sync.hasSnapshot()) {
                    screen = SuitePlatform.screen as? SyncAcceptanceScreen ?: error("The synchronized menu did not open its screen")
                    check(menu.counter == 7 && menu.text == SyncAcceptanceMenu.INITIAL) { "The initial snapshot is wrong" }
                    check(menu.sync.statistics().largestBatch() <= menu.sync.options().state().batchBytes()) { "A state batch exceeded its bound" }
                    check(menu.sync.request(SyncAcceptanceMenu.REPLACE, SyncAcceptanceMenu.REPLACEMENT).queued()) { "The action was not queued" }
                    stage++
                }
            }
            2 -> {
                val menu = mc.player!!.containerMenu as SyncAcceptanceMenu
                check(menu.sync.status() != MenuSync.Status.FAILED) { "Menu sync failed: ${menu.sync.statistics()}" }
                val result = menu.sync.lastActionResult()
                if (result != null && menu.counter == 42 && menu.text == SyncAcceptanceMenu.REPLACEMENT) {
                    check(result.status() == MenuSync.ActionStatus.APPLIED) { "The action was not applied: ${result.status()}" }
                    check(menu.sync.statistics().largestBatch() <= menu.sync.options().state().batchBytes()) { "A state batch exceeded its bound" }
                    mc.player!!.closeContainer()
                    stage++
                }
            }
            3 -> if (mc.player?.containerMenu === mc.player?.inventoryMenu) {
                val closed = checkNotNull(screen)
                check(closed.session == null && closed.rendererStatistics.liveSurfaces == 0) { "The menu screen kept its renderer after closing" }
                stage++
                done()
            }
        }
    }
}
