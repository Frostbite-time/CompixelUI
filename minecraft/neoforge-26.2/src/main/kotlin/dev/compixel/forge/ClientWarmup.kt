package dev.compixel.forge

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojang.logging.LogUtils
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.forge.item.MinecraftItemTooltip
import dev.compixel.forge.slots.ComposeMenuSlots
import dev.compixel.host.ScreenTransition
import dev.compixel.host.UiStateBinding
import dev.compixel.host.internal.UiWarmup
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.internal.OreWarmUpContent
import dev.compixel.ui.ore.layout.OrePanel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Prepares the UI before the player's first screen, on the client thread and with the game's own graphics: a hidden
 * Compose layer goes through what real screens do, one step in each frame the game draws. It composes text, Ore's
 * controls and a container of real slots with items, glint, amounts, hover, a drag preview, scrolling and a native
 * tooltip, and every frame takes the whole path of a real one: layout, native images, recording and the GPU. The frames
 * are presented beside the screen, where nothing of them shows. Afterwards the layer closes; what stays is what later
 * screens reuse: loaded classes, the shared Skia context with its compiled shaders, and warm game code.
 *
 * It has two parts, each with a layer of its own. The controls start once the game has loaded its resources. The
 * container follows when the game can make item stacks, and both wait while a CompixelUI screen is open or plays its
 * exit: that screen has the frame. A failure ends a part with a warning and never reaches the game.
 */
internal object ClientWarmup {
    private const val ROWS = 6
    private const val COLUMNS = 9
    private val logger = LogUtils.getLogger()
    // The part that runs now, and those that follow it.
    private var steps: UiWarmup? = null
    private val parts = ArrayDeque(listOf(::controls, ::container))
    private var done = !UiWarmup.enabled
    private var failed = false
    private var frames = 0
    private var totalNanos = 0L
    private var longestNanos = 0L
    private var longestStep = ""
    private var scene by mutableIntStateOf(0)
    private var layer: ComposeLayer? = null
    private var slots: ComposeMenuSlots<WarmupMenu>? = null
    private var graphics: GuiGraphicsExtractor? = null
    private var dragged = emptySet<Int>()

    /** "pending" until the warm-up starts, then "running", and "done" or "failed" once it ended. */
    var state = if (done) "off" else "pending"
        private set

    /** What the warm-up took, once it ended. */
    var summary = ""
        private set

    /** Runs the next step in a frame that draws [graphics], unless the game still loads or a real UI is open. */
    fun frame(graphics: GuiGraphicsExtractor) {
        if (done) return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.overlay() != null || minecraft.window.width <= 0 || minecraft.window.height <= 0) return
        val screen = minecraft.gui.screen()
        if (screen is ComposeScreen || screen is ComposeInventoryScreen<*> || ScreenExits.playing) return
        // The container needs item stacks, which some versions can make only once a world gave them their components.
        if (steps == null && parts.size == 1 && !itemsReady()) return
        val part = steps ?: parts.removeFirst().invoke().also { steps = it }
        state = "running"
        this.graphics = graphics
        try {
            if (part.advance()) return
        } finally {
            this.graphics = null
        }
        steps = null
        failed = failed || part.failure != null
        frames += part.frames
        totalNanos += part.totalNanos
        if (part.longestNanos > longestNanos) {
            longestNanos = part.longestNanos
            longestStep = part.longestStep
        }
        if (parts.isNotEmpty()) return
        done = true
        state = if (failed) "failed" else "done"
        summary =
            "$frames frames, ${totalNanos / 1_000_000} ms; longest step \"$longestStep\" ${longestNanos / 1_000_000} ms"
        logger.info("CompixelUI warm-up {}: {}", state, summary)
    }

    /** The game stops: what the warm-up has open is released now, and it does not go on. */
    fun abandon() {
        if (done) return
        done = true
        steps = null
        state = "off"
        try {
            close()
        } catch (error: Throwable) {
            logger.warn("CompixelUI warm-up did not close", error)
        }
    }

    // Item stacks need their components, which a world binds.
    private fun itemsReady() = Items.DIAMOND.builtInRegistryHolder().areComponentsBound()

    private fun part() = UiWarmup { error -> logger.warn("CompixelUI warm-up failed", error) }

    private fun controls(): UiWarmup {
        val steps = part()
        steps.step("open the controls") {
            scene = 0
            open(null)
        }
        steps.step("first text") { draw() }
        steps.step("Ore controls") {
            scene = 1
            draw()
        }
        // The pointer crosses the controls, so hover and hit testing run while the entrance plays.
        repeat(10) { frame ->
            steps.step("Ore controls, frame ${frame + 2}") {
                val window = Minecraft.getInstance().window
                val progress = (frame + 1) / 10.0
                checkNotNull(layer).move(window.guiScaledWidth * progress, window.guiScaledHeight * progress)
                draw()
            }
        }
        steps.step("close the controls", always = true) { close() }
        return steps
    }

    private fun container(): UiWarmup {
        val steps = part()
        steps.step("open the container") {
            scene = 2
            open(ComposeMenuSlots(WarmupMenu()))
        }
        steps.step("container") { draw() }
        // On a deferred graphics device the icons drawn in one frame show in the next.
        repeat(3) { frame -> steps.step("container icons, frame ${frame + 2}") { draw() } }
        repeat(4) { frame ->
            steps.step("slot hover ${frame + 1}") {
                hover(frame * (COLUMNS + 1))
                draw()
            }
        }
        steps.step("drag preview") {
            checkNotNull(slots).menu.carried = ItemStack(Items.DIAMOND, 12)
            dragged = setOf(1, 2, 3)
            draw()
        }
        steps.step("drag preview ends") {
            checkNotNull(slots).menu.carried = ItemStack.EMPTY
            dragged = emptySet()
            draw()
        }
        repeat(3) { frame ->
            steps.step("scroll ${frame + 1}") {
                val window = Minecraft.getInstance().window
                checkNotNull(layer).scroll(window.guiScaledWidth / 2.0, window.guiScaledHeight / 2.0, 0.0, -1.0)
                draw()
            }
        }
        // The pointer rests on the item whose native tooltip then shows.
        steps.until("native tooltip", atMost = 40) {
            tooltipBounds()?.let { checkNotNull(layer).move((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) }
            draw()
            checkNotNull(layer).nativeTooltipStatistics.visible
        }
        repeat(2) { frame -> steps.step("native tooltip, frame ${frame + 2}") { draw() } }
        steps.step("close the container", always = true) { close() }
        return steps
    }

    private fun open(inventory: ComposeMenuSlots<WarmupMenu>?) {
        val window = Minecraft.getInstance().window
        slots = inventory
        layer =
            ComposeLayer(
                    // The pointer moves over it as over a focused screen; it holds no text field.
                    windowFocused = { true },
                    prepareFrameContent = { inventory?.refreshAfterLayout() == true },
                    contentState = UiStateBinding(),
                ) {
                    // Screens commonly slide in as they fade.
                    ScreenTransition(
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut() + slideOutVertically { it / 4 },
                    ) {
                        when (scene) {
                            0 -> OreText("CompixelUI")
                            1 -> OreWarmUpContent()
                            else -> Container(checkNotNull(inventory))
                        }
                    }
                }
                .also { it.open(window.guiScaledWidth, window.guiScaledHeight) }
    }

    // One whole frame, as a container screen draws it, presented beside the screen.
    private fun draw() {
        val window = Minecraft.getInstance().window
        val active = checkNotNull(layer)
        val graphics = checkNotNull(graphics)
        val width = window.guiScaledWidth
        val height = window.guiScaledHeight
        slots?.let {
            it.viewport(active.currentMetrics(width, height))
            it.nativeInteraction(0, dragged)
            it.refresh()
        }
        // The frame is drawn with the GUI, so it goes where the screen ends: the GPU still samples and blends it.
        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(-4f * width, 0f)
            active.render(graphics, width, height)
        } finally {
            graphics.pose().popMatrix()
        }
    }

    private fun hover(slot: Int) {
        val bounds = checkNotNull(slots).bounds(slot) ?: return
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        checkNotNull(slots).move(x, y)
        checkNotNull(layer).move(x, y)
    }

    // The tooltip item sits right of the first slot row's last slot; null until the layout placed that slot.
    private fun tooltipBounds(): DoubleArray? {
        val last = checkNotNull(slots).bounds(COLUMNS - 1) ?: return null
        val step = last.right - last.left
        return doubleArrayOf(last.left + step, last.top, last.right + step, last.bottom)
    }

    private fun close() {
        try {
            layer?.close()
        } finally {
            layer = null
            try {
                slots?.close()
            } finally {
                slots = null
            }
        }
    }

    @Composable
    private fun Container(inventory: ComposeMenuSlots<WarmupMenu>) {
        val tooltip = WarmupMenu.tooltipIcon
        OrePanel("CompixelUI", Modifier.fillMaxSize()) {
            // Shorter than its rows, so the wheel scrolls it.
            Column(
                Modifier.height(72.dp).verticalScroll(rememberScrollState()).then(inventory.areaModifier()),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                repeat(ROWS) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(COLUMNS) { column -> inventory.Slot(row * COLUMNS + column) }
                        if (row == 0)
                            MinecraftItemTooltip(tooltip, delayMillis = 0) {
                                Box(Modifier.padding(start = 1.dp)) { MinecraftItemIcon(tooltip, Modifier.size(18.dp)) }
                            }
                    }
                }
            }
        }
    }
}

/** A container that exists only for the warm-up: no player has it open, and nothing is sent anywhere. */
private class WarmupMenu : AbstractContainerMenu(null, -1) {
    init {
        val items = SimpleContainer(54)
        val stacks =
            listOf(
                ItemStack(Items.DIAMOND, 64),
                ItemStack(Items.IRON_INGOT, 32),
                glinting(Items.DIAMOND_SWORD),
                ItemStack(Items.OAK_PLANKS, 16),
                ItemStack(Items.CHEST),
                glinting(Items.BOOK),
                ItemStack(Items.REDSTONE, 7),
                ItemStack(Items.SHIELD),
            )
        // Every third slot stays empty.
        for (index in 0 until items.containerSize) {
            if (index % 3 != 2) items.setItem(index, stacks[index % stacks.size].copy())
            addSlot(Slot(items, index, 0, 0))
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player) = true

    companion object {
        val tooltipIcon: ItemIcon by lazy { ItemIcon.snapshot(glinting(Items.DIAMOND_PICKAXE)) }

        private fun glinting(item: net.minecraft.world.item.Item) =
            ItemStack(item).apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
    }
}
