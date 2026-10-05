package dev.compixel.development

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeInventoryScreen
import dev.compixel.forge.ComposeScreen
import dev.compixel.forge.ScreenExits
import dev.compixel.forge.slots.ComposeMenuSlots
import dev.compixel.host.ScreenTransition
import dev.compixel.host.SessionState
import dev.compixel.host.UiSession
import dev.compixel.testing.suite.AcceptanceLog
import dev.compixel.testing.suite.AcceptanceStep
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.InventoryMenu

// Identical in every adapter; version differences belong in SuitePlatform.kt.

/** What a ScreenTransition's content showed: its composition, its latest phase, and when it composed and entered. */
internal data class TransitionShown(
    val composition: Any,
    val phase: EnterExitState,
    val composedAt: Long,
    val enteredAt: Long,
    /** The content's compositions so far; each one entered from the start. */
    val entrances: Int,
) {
    companion object {
        fun next(previous: TransitionShown?, composition: Any, phase: EnterExitState): TransitionShown {
            val now = System.nanoTime()
            val visible = phase == EnterExitState.Visible
            if (previous == null || previous.composition !== composition)
                return TransitionShown(
                    composition,
                    phase,
                    now,
                    if (visible) now else 0L,
                    (previous?.entrances ?: 0) + 1,
                )
            val entered = if (previous.enteredAt == 0L && visible) now else previous.enteredAt
            return previous.copy(phase = phase, enteredAt = entered)
        }
    }
}

/** A suite screen whose content enters and exits through [ReportedTransition]. */
internal interface ShowsTransition {
    var shown: TransitionShown?
}

/** A ScreenTransition that reports what its content showed. */
@Composable
internal fun ShowsTransition.ReportedTransition(
    enter: EnterTransition = fadeIn(tween(TransitionAcceptance.ENTER_MILLIS)),
    exit: ExitTransition = fadeOut(tween(TransitionAcceptance.EXIT_MILLIS)),
    content: @Composable () -> Unit,
) {
    ScreenTransition(enter = enter, exit = exit) {
        val composition = remember { Any() }
        val phase = transition.currentState
        SideEffect { shown = TransitionShown.next(shown, composition, phase) }
        content()
    }
}

/** The player's own inventory menu, whose hotbar slides and fades in and out. */
internal class InventoryTransitionScreen :
    ComposeInventoryScreen<InventoryMenu, Unit, Nothing>(
        checkNotNull(Minecraft.getInstance().player).inventoryMenu,
        Component.literal("Inventory transition acceptance"),
    ),
    ShowsTransition {
    @Volatile override var shown: TransitionShown? = null

    /** Calls of [menuClosed]; the menu closes once. */
    var menuCloses = 0
        private set

    override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    override fun snapshot() = Unit

    override fun handle(action: Nothing) {}

    override fun menuClosed() {
        menuCloses++
    }

    @Composable
    override fun Content(state: Unit, slots: ComposeMenuSlots<InventoryMenu>) =
        ReportedTransition(
            enter =
                fadeIn(tween(TransitionAcceptance.ENTER_MILLIS)) +
                    slideInVertically(tween(TransitionAcceptance.ENTER_MILLIS)) { it / 8 },
            exit =
                fadeOut(tween(TransitionAcceptance.EXIT_MILLIS)) +
                    slideOutVertically(tween(TransitionAcceptance.EXIT_MILLIS)) { it / 8 },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(Modifier.background(Color(0xFF204060)).then(slots.areaModifier())) {
                    for (id in 36..44) slots.Slot(id)
                }
            }
        }
}

/** A screen without a menu. */
internal class PlainTransitionScreen :
    ComposeScreen<Unit, Nothing>(Component.literal("Plain transition acceptance")), ShowsTransition {
    @Volatile override var shown: TransitionShown? = null

    override fun isUiWindowFocused() = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    override fun snapshot() = Unit

    override fun handle(action: Nothing) {}

    @Composable
    override fun Content(state: Unit) = ReportedTransition { Box(Modifier.fillMaxSize().background(Color(0x80204060))) }
}

/**
 * Screen transitions. A menu screen that another screen covers, as a recipe viewer does, keeps its session and shows it
 * again without entering again; it releases the session when its menu closes beneath the cover, or when the cover ends
 * without it. Closing gives the player control back at once while the exit plays above the game, and the exit releases
 * everything when it ends, also when the same menu, another menu or the same screen opens during the exit.
 */
internal class TransitionAcceptance(private val log: AcceptanceLog, private val script: SuiteScript) {
    private val minecraft
        get() = Minecraft.getInstance()

    private val server
        get() = checkNotNull(minecraft.singleplayerServer)

    /** A screen that closed for good while its content plays the exit. */
    private class Exiting(val screen: Screen, val frames: Long, val entrances: Int) {
        val closedAt = System.nanoTime()
    }

    private lateinit var menu: SyncAcceptanceScreen
    private lateinit var inventoryScreen: InventoryTransitionScreen
    private lateinit var plain: PlainTransitionScreen
    private lateinit var kept: UiSession
    private var composition: Any? = null
    private var exiting: Exiting? = null
    private var serverClosed: CompletableFuture<Boolean>? = null
    // Each finished exit's length and the frames it drew, for the report.
    private val exits = ArrayList<String>()

    fun schedule() {
        covered()
        coverOutlivesMenu()
        reopen()
        anotherMenu()
        inventory()
        sameScreen()
        script.act("record ${AcceptanceStep.TRANSITIONS}") {
            log.pass(AcceptanceStep.TRANSITIONS, "exits: " + exits.joinToString())
        }
    }

    private fun covered() {
        // The server closes whatever menu is open when any close packet arrives, including the one the menu sync
        // step's screen just sent. Open the menu only after the server has handled that packet.
        script.until("the server handled the previous menu's close") { serverHandledClose() }
        script.act("open a menu whose screen uses a ScreenTransition") {
            SyncAcceptanceScreen.transitions = true
            openMenu()
        }
        script.until("the menu screen entered") { menuEntered() }
        script.until("the menu's snapshot reached the content") { ComposeThread.call { menu.composed }?.ready == true }
        script.act("cover the menu screen") {
            kept = checkNotNull(menu.session)
            composition = checkNotNull(menu.shown).composition
            SuitePlatform.setScreen(SuiteParentScreen())
            check(menu.session === kept) { "Covering the menu screen ended its session" }
        }
        // Ticks go on beneath the cover while the menu stays open.
        script.pause(200)
        script.act("show the covered menu screen again") {
            check(menu.session === kept && menu.menuCloses == 0) {
                "The covered menu screen was released while its menu stayed open"
            }
            SuitePlatform.setScreen(menu)
            check(menu.session === kept) { "The menu screen did not show its session again" }
        }
        script.pause(100)
        script.act("click the menu screen shown again") {
            val shown = checkNotNull(menu.shown)
            check(shown.composition === composition && shown.entrances == 1 && shown.phase == EnterExitState.Visible) {
                "The menu screen entered again after its cover: $shown"
            }
            val x = menu.width / 2.0
            val y = menu.height / 2.0
            menu.mouseMoved(x, y)
            menu.mouseClicked(x, y, SuitePlatform.MOUSE_LEFT)
            menu.mouseReleased(x, y, SuitePlatform.MOUSE_LEFT)
            check(menu.requests == 1) { "The menu screen shown again handled ${menu.requests} clicks" }
        }
    }

    private fun coverOutlivesMenu() {
        script.act("cover the menu screen, then close its menu on the server") {
            SuitePlatform.setScreen(SuiteParentScreen())
            server.execute { server.playerList.players.first().closeContainer() }
        }
        script.until("the covered menu screen released its session") { menu.session == null }
        script.act("check the menu screen that closed beneath its cover") {
            check(menu.menuCloses == 1) { "The menu that closed beneath a cover reported ${menu.menuCloses} closes" }
            released(menu, "The menu screen that closed beneath a cover")
            SuitePlatform.setScreen(null)
        }
    }

    // The same menu opens again as soon as the server has handled the close.
    private fun reopen() {
        script.act("open the menu again") { openMenu() }
        script.until("the new menu screen entered") { menuEntered(other = menu) }
        script.act("close the menu screen") { closeAtOnce(menu) }
        script.until("the server handled the close") { serverHandledClose() }
        script.act("open the same menu again at once") { openMenu() }
        script.until("the menu opened again during the exit") {
            val reopened = SuitePlatform.screen.let { it is SyncAcceptanceScreen && it !== menu }
            if (reopened) check(menu.session != null) { "The exit had finished before the menu opened again" }
            reopened
        }
        script.until("the exit finished beneath the menu opened again", 20_000) { exitReleased(menu) }
    }

    // Another menu, with a vanilla screen, opens as soon as the server has handled the close.
    private fun anotherMenu() {
        script.until("the menu opened again entered") { menuEntered(other = menu) }
        script.act("close the menu screen opened again") { closeAtOnce(menu) }
        script.until("the server handled the close") { serverHandledClose() }
        script.act("open a chest at once") {
            SyncAcceptanceScreen.transitions = false
            server.execute {
                server.playerList.players
                    .first()
                    .openMenu(
                        SimpleMenuProvider(
                            { id, inventory, _ -> ChestMenu.threeRows(id, inventory) },
                            Component.literal("Transition chest"),
                        )
                    )
            }
        }
        script.until("the chest opened during the exit") {
            val chest = SuitePlatform.screen is ContainerScreen
            if (chest) check(menu.session != null) { "The exit had finished before the chest opened" }
            chest
        }
        script.until("the exit finished beneath the chest", 20_000) { exitReleased(menu) }
        script.act("close the chest") { checkNotNull(SuitePlatform.screen).onClose() }
    }

    // The player's own inventory menu stays open without its screen: a cover and reopening concern the screen alone.
    private fun inventory() {
        lateinit var previous: InventoryTransitionScreen
        script.act("open the inventory") {
            inventoryScreen = InventoryTransitionScreen()
            SuitePlatform.setScreen(inventoryScreen)
        }
        script.until("the inventory entered") { entered(inventoryScreen) }
        script.act("cover the inventory, then end the cover without it") {
            kept = checkNotNull(inventoryScreen.session)
            SuitePlatform.setScreen(SuiteParentScreen())
            check(inventoryScreen.session === kept && inventoryScreen.menuCloses == 0) {
                "Covering the inventory ended its session"
            }
            SuitePlatform.setScreen(null)
        }
        script.until("the inventory released its session after its cover") { inventoryScreen.session == null }
        script.act("open the inventory after its cover ended") {
            check(inventoryScreen.menuCloses == 1) {
                "The inventory whose cover ended reported ${inventoryScreen.menuCloses} closes"
            }
            released(inventoryScreen, "The inventory whose cover ended")
            inventoryScreen = InventoryTransitionScreen()
            SuitePlatform.setScreen(inventoryScreen)
        }
        script.until("the inventory entered after its cover ended") { entered(inventoryScreen) }
        script.act("close the inventory and open it again at once") {
            previous = inventoryScreen
            closeAtOnce(previous)
            inventoryScreen = InventoryTransitionScreen()
            SuitePlatform.setScreen(inventoryScreen)
        }
        script.until("the inventory opened again laid out its slots during the exit") {
            val laidOut = inventoryScreen.inventory.bounds(37) != null
            if (laidOut) check(previous.session != null) { "The exit had finished before the inventory laid out" }
            laidOut
        }
        script.until("the exit finished beneath the inventory opened again", 20_000) { exitReleased(previous) }
        script.until("the inventory opened again entered") { entered(inventoryScreen) }
        script.act("close the inventory opened again") { closeAtOnce(inventoryScreen) }
        script.until("the inventory's exit finished", 20_000) { exitReleased(inventoryScreen) }
    }

    // The same screen shows again during its exit: the exit ends there, and the screen enters from the start.
    private fun sameScreen() {
        script.act("open a screen without a menu") {
            plain = PlainTransitionScreen()
            SuitePlatform.setScreen(plain)
        }
        script.until("the screen without a menu entered") { entered(plain) }
        script.act("close the screen and show it again at once") {
            closeAtOnce(plain)
            val first = checkNotNull(plain.session)
            // A screen that something removes again during its exit, as a few mods do, keeps one exit.
            plain.removed()
            check(ScreenExits.size == 1) { "Removing the screen again added ${ScreenExits.size - 1} exits" }
            SuitePlatform.setScreen(plain)
            check(ScreenExits.size == 0) { "Showing the screen again left ${ScreenExits.size} exits behind" }
            exiting = null
            val second = checkNotNull(plain.session) { "The screen shown again during its exit has no session" }
            check(second !== first && first.state == SessionState.CLOSED) {
                "Showing the screen again during its exit kept the exiting session"
            }
        }
        script.until("the screen shown again entered from the start") { plain.shown?.entrances == 2 && entered(plain) }
        script.act("close the screen shown again") { closeAtOnce(plain) }
        script.until("the screen's exit finished", 20_000) { exitReleased(plain) }
    }

    // A menu screen the server opened, other than [other], has entered.
    private fun menuEntered(other: Screen? = null): Boolean {
        val screen = (SuitePlatform.screen as? SyncAcceptanceScreen)?.takeIf { it !== other } ?: return false
        if (!entered(screen)) return false
        menu = screen
        return true
    }

    // The screen has shown its content in full, after an entrance that took its time.
    private fun entered(screen: Screen): Boolean {
        val shown = (screen as ShowsTransition).shown ?: return false
        if (shown.phase != EnterExitState.Visible || shown.enteredAt == 0L) return false
        val millis = (shown.enteredAt - shown.composedAt) / 1_000_000
        check(millis >= ENTER_MILLIS * 3 / 4) { "${screen.title.string} entered after $millis ms" }
        return true
    }

    // Closes as Escape does: the player has control again at once, while the exit goes on drawing.
    private fun closeAtOnce(screen: Screen) {
        check(exiting == null) { "Another exit is still being checked" }
        val frames = renderedFrames(screen)
        screen.onClose()
        check(SuitePlatform.screen == null) { "Closing ${screen.title.string} did not give the player control back" }
        val closes =
            when (screen) {
                is SyncAcceptanceScreen -> screen.menuCloses
                is InventoryTransitionScreen -> screen.menuCloses
                else -> 1
            }
        check(closes == 1) { "${screen.title.string} reported $closes menu closes" }
        check(sessionOf(screen) != null) { "${screen.title.string} did not play its exit" }
        exiting = Exiting(screen, frames, checkNotNull((screen as ShowsTransition).shown).entrances)
        serverClosed = null
    }

    // The exit drew for its whole length after its screen closed, then released everything.
    private fun exitReleased(screen: Screen): Boolean {
        val exit = checkNotNull(exiting)
        check(exit.screen === screen) { "${screen.title.string} is not the screen whose exit plays" }
        if (sessionOf(screen) != null) return false
        val millis = (System.nanoTime() - exit.closedAt) / 1_000_000
        check(millis >= EXIT_MILLIS * 3 / 4) { "${screen.title.string} ended its exit after $millis ms" }
        val frames = renderedFrames(screen) - exit.frames
        check(frames >= 3) { "${screen.title.string} drew $frames frames during its exit" }
        check((screen as ShowsTransition).shown?.entrances == exit.entrances) {
            "${screen.title.string} entered again during its exit"
        }
        released(screen, "${screen.title.string} after its exit")
        exits += "$millis ms/$frames frames"
        exiting = null
        return true
    }

    private fun serverHandledClose(): Boolean {
        val pending =
            serverClosed
                ?: server
                    .submit(
                        Supplier {
                            val player = server.playerList.players.first()
                            player.containerMenu === player.inventoryMenu
                        }
                    )
                    .also { serverClosed = it }
        if (!pending.isDone) return false
        serverClosed = null
        return pending.join()
    }

    private fun sessionOf(screen: Screen): UiSession? =
        when (screen) {
            is ComposeScreen<*, *> -> screen.session
            is ComposeInventoryScreen<*, *, *> -> screen.session
            else -> error("${screen.title.string} is not a Compose screen")
        }

    private fun renderedFrames(screen: Screen): Long =
        when (screen) {
            is ComposeScreen<*, *> -> screen.rendererStatistics.renderedFrames
            is ComposeInventoryScreen<*, *, *> -> screen.rendererStatistics.renderedFrames
            else -> error("${screen.title.string} is not a Compose screen")
        }

    private fun released(screen: Screen, what: String) {
        val (renderer, items) =
            when (screen) {
                is ComposeScreen<*, *> -> screen.rendererStatistics to screen.nativeItemStatistics
                is ComposeInventoryScreen<*, *, *> -> screen.rendererStatistics to screen.nativeItemStatistics
                else -> error("$what: not a Compose screen")
            }
        check(renderer.liveSurfaces == 0 && renderer.liveNativeImages == 0 && renderer.strandedNativeImages == 0) {
            "$what kept renderer resources: $renderer"
        }
        check(items.preparedImages == items.retiredImages) { "$what kept native item images: $items" }
    }

    private fun openMenu() = server.execute {
        server.playerList.players
            .first()
            .openMenu(
                SimpleMenuProvider(
                    { id, inventory, _ -> SyncAcceptanceMenu(id, inventory) },
                    Component.literal("Transition acceptance"),
                )
            )
    }

    companion object {
        const val ENTER_MILLIS = 400

        /** Long enough that a menu the server opens after handling the close still overlaps the exit. */
        const val EXIT_MILLIS = 1000
    }
}
