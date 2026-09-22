package dev.composemc.development

import dev.composemc.forge.sync.MenuSync
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory

internal class SyncAcceptanceScreen(menu: SyncAcceptanceMenu, inventory: Inventory, title: Component) :
    dev.composemc.forge.ForgeComposeMenuScreen<SyncAcceptanceMenu>(menu, title, content = {}) {
    override fun isUiWindowFocused() = true
}
internal class MenuSyncAcceptanceProbe(private val done: () -> Unit) {
    private val mc get() = Minecraft.getInstance()
    private var ticks = 0
    private var stage = 0
    fun tick() {
        check(++ticks < 600) { "Menu transport acceptance timed out at stage $stage" }
        when(stage) {
            0 -> {
                MenuScreens.register(SyncAcceptanceMenu.TYPE.get(), ::SyncAcceptanceScreen)
                mc.singleplayerServer!!.execute {
                    val player = mc.singleplayerServer!!.playerList.players.first()
                    player.openMenu(SimpleMenuProvider({ id, inventory, _ -> SyncAcceptanceMenu(id, inventory) },
                        Component.literal("Menu transport acceptance")))
                }
                stage++
            }
            1 -> (mc.player?.containerMenu as? SyncAcceptanceMenu)?.let { menu ->
                check(menu.sync.status() != MenuSync.Status.FAILED) { menu.sync.statistics().failure() }
                if (menu.sync.hasSnapshot()) {
                    check(menu.counter == 7 && menu.text == SyncAcceptanceMenu.INITIAL)
                    check(menu.sync.statistics().largestBatch() <= menu.sync.options().state().batchBytes())
                    check(menu.sync.request(SyncAcceptanceMenu.REPLACE, SyncAcceptanceMenu.REPLACEMENT).queued())
                    stage++
                }
            }
            2 -> {
                val menu = mc.player!!.containerMenu as SyncAcceptanceMenu
                check(menu.sync.status() != MenuSync.Status.FAILED) { menu.sync.statistics().failure() }
                val result = menu.sync.lastActionResult()
                if (result != null && menu.counter == 42 && menu.text == SyncAcceptanceMenu.REPLACEMENT) {
                    check(result.status() == MenuSync.ActionStatus.APPLIED)
                    check(menu.sync.statistics().largestBatch() <= menu.sync.options().state().batchBytes())
                    mc.player!!.closeContainer()
                    stage++
                }
            }
            3 -> if (mc.player?.containerMenu === mc.player?.inventoryMenu) { stage++; done() }
        }
    }
}
