package dev.compixel.forge.slots

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.drawing.NativeRefresh
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.host.ScreenMetrics
import dev.compixel.slots.*
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.inventory.OreSlot
import dev.compixel.ui.ore.theme.OreTheme
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

data class MenuSlotVisual(
    val icon: ItemIcon? = null,
    val amount: String = "",
    val marked: Boolean = false,
    val compactAmount: String = amount,
)

/**
 * What a slot shows, for drawing it yourself with [ComposeMenuSlots.Slot]: the adapter's [MenuSlotVisual] values and
 * the slot's interaction state.
 */
@Immutable
data class MenuSlotState(
    val icon: ItemIcon?,
    /** The amount label, and a shorter one for narrow slots. */
    val amount: String,
    val compactAmount: String,
    /** Whether the adapter marked the slot, for example as a filter. */
    val marked: Boolean,
    /** Whether a native drag spreads the carried stack over this slot. */
    val highlighted: Boolean,
    /** Whether the pointer is over the slot. */
    val hovered: Boolean,
)

data class MenuSlotBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Mouse events use rounded GUI dimensions; native rendering uses the exact GUI scale. */
internal fun menuInputBounds(pixels: Rect, metrics: ScreenMetrics) =
    MenuSlotBounds(
        metrics.inputX(pixels.left),
        metrics.inputY(pixels.top),
        metrics.inputX(pixels.right),
        metrics.inputY(pixels.bottom),
    )

internal fun menuRenderBounds(pixels: Rect, metrics: ScreenMetrics) =
    MenuSlotBounds(
        metrics.renderCoordinate(pixels.left.toDouble()),
        metrics.renderCoordinate(pixels.top.toDouble()),
        metrics.renderCoordinate(pixels.right.toDouble()),
        metrics.renderCoordinate(pixels.bottom.toDouble()),
    )

/** Public game-thread extension point for item/resource snapshots and the consumer's existing protocol. */
interface MenuSlotAdapter {
    fun visual(slot: Slot, previous: MenuSlotVisual?): MenuSlotVisual

    fun behavior(slot: Slot?): SlotBehavior = SlotBehavior.standard()

    fun canDragTo(slot: Slot): Boolean = true

    fun execute(slotId: Int, button: Int, type: ClickType)

    fun localAction(slot: Slot?, action: SlotIntent.Local) {}

    fun tooltip(graphics: GuiGraphics, slot: Slot, x: Int, y: Int) {
        if (!slot.item.isEmpty) graphics.renderTooltip(Minecraft.getInstance().font, slot.item, x, y)
    }
}

/** Default adapter for any ordinary vanilla-backed menu. It owns only immutable icon caches. */
open class VanillaMenuSlotAdapter(private val menu: AbstractContainerMenu) : MenuSlotAdapter {
    private val stacks = mutableMapOf<Int, ItemStack>()
    private val emptyIcons =
        mutableMapOf<
            Int,
            Pair<
                com.mojang.datafixers.util.Pair<
                    net.minecraft.resources.ResourceLocation,
                    net.minecraft.resources.ResourceLocation,
                >,
                ItemIcon,
            >,
        >()

    /** Empty armor/offhand and consumer template slots retain the native slot's atlas hint. */
    protected fun emptyIcon(slot: Slot): ItemIcon? {
        val sprite =
            slot.noItemIcon
                ?: run {
                    emptyIcons.remove(slot.index)
                    return null
                }
        emptyIcons[slot.index]
            ?.takeIf { it.first == sprite }
            ?.let {
                return it.second
            }
        val icon =
            ItemIcon.drawn(
                "slot:${sprite.first}/${sprite.second}",
                { graphics ->
                    graphics.blit(
                        0,
                        0,
                        0,
                        16,
                        16,
                        Minecraft.getInstance().getTextureAtlas(sprite.first).apply(sprite.second),
                    )
                },
                NativeRefresh.STATIC,
            )
        emptyIcons[slot.index] = sprite to icon
        return icon
    }

    override fun visual(slot: Slot, previous: MenuSlotVisual?): MenuSlotVisual {
        val stack = slot.item
        if (stack.isEmpty) {
            stacks.remove(slot.index)
            return MenuSlotVisual(icon = emptyIcon(slot))
        }
        val old = stacks[slot.index] ?: ItemStack.EMPTY
        if (previous != null && ItemStack.matches(old, stack)) return previous
        val copy = stack.copy()
        stacks[slot.index] = copy
        if (previous?.icon != null && ItemStack.isSameItemSameTags(old, copy))
            return previous.copy(
                amount = if (copy.count > 1) copy.count.toString() else "",
                compactAmount = if (copy.count > 1) copy.count.toString() else "",
            )
        return MenuSlotVisual(
            ItemIcon.snapshot(copy.copyWithCount(1)),
            if (copy.count > 1) copy.count.toString() else "",
        )
    }

    override fun execute(slotId: Int, button: Int, type: ClickType) {
        if ((menu as? dev.compixel.forge.sync.SyncedMenu)?.menuSync()?.isSendingAction() == true) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.containerMenu === menu)
            mc.gameMode?.handleInventoryMouseClick(menu.containerId, slotId, button, type, player)
    }
}

/** Compose placement is mirrored as clipped hit regions; all live menu access stays on the game thread. */
class ComposeMenuSlots<M : AbstractContainerMenu>(
    val menu: M,
    val adapter: MenuSlotAdapter = VanillaMenuSlotAdapter(menu),
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val slotCount = menu.slots.size
    private val layoutLock = Any()
    private val layout = linkedMapOf<Int, Pair<Any, Rect>>()
    private var layoutRevision = 0L
    private var loadedLayoutRevision = -1L
    private var area: Rect? = null
    private val blocked = AtomicBoolean(false)
    private var currentLayout = emptyMap<Int, Rect>()
    private val visuals = mutableStateMapOf<Int, MenuSlotVisual>()
    private val snapshots = mutableMapOf<Int, MenuSlotVisual>()
    private val shown = mutableMapOf<Int, MenuSlotVisual>()
    private var metrics = ScreenMetrics(1, 1, 1, 1, 1f, 1f)
    private var cursor = ItemStack.EMPTY
    private var cursorIcon: ItemIcon? = null
    private var closed = false
    private var nativeActive = false
    private var nativeDragIds: Set<Int> = emptySet()
    private var nativeButton = 0
    private var highlighted by mutableStateOf(-1)
    val interacting
        get() = nativeActive

    val interactionsEnabled
        get() = !blocked.get()

    val visibleSlotIds: Set<Int>
        get() = currentLayout.keys

    @Composable
    fun Interaction(enabled: Boolean = true) {
        SideEffect { blocked.set(!enabled) }
    }

    fun areaModifier(): Modifier = Modifier.onGloballyPositioned { position ->
        synchronized(layoutLock) { area = position.boundsInRoot() }
    }

    /** A slot in Ore's look: its frame, item icon and amount. */
    @Composable
    fun Slot(slotId: Int, modifier: Modifier = Modifier) {
        val region = slotRegion(slotId)
        val visual = visuals[slotId] ?: MenuSlotVisual()
        OreSlot(
            modifier.then(region),
            marked = visual.marked,
            highlighted = highlighted == slotId,
            contentModifier = Modifier.size(16.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                visual.icon?.let { MinecraftItemIcon(it, Modifier.fillMaxSize()) }
                val amount = if (maxWidth < 19.dp) visual.compactAmount else visual.amount
                // Amount labels formerly shared the adapter's 0.5 density. With one dp now
                // representing one Minecraft GUI unit, retain their intentionally compact
                // inventory treatment rather than doubling their visual footprint.
                val font =
                    (minOf(12f, (maxWidth.value - 1) / (amount.length.coerceAtLeast(1) * 0.7f)).coerceAtLeast(8f) / 2f)
                        .sp
                if (amount.isNotEmpty())
                    OreText(
                        amount,
                        Modifier.align(Alignment.BottomEnd),
                        style = OreTheme.typography.caption.copy(fontSize = font),
                        maxLines = 1,
                    )
            }
        }
    }

    /**
     * A slot that draws itself: [content] gets what the slot shows and fills the bounds that [modifier] gives the slot,
     * for example `Modifier.size(18.dp)`. Clicks, drags, quick moves and tooltips work as for a slot in Ore's look.
     * Draw the item with [MinecraftItemIcon].
     */
    @Composable
    fun Slot(slotId: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.(MenuSlotState) -> Unit) {
        val region = slotRegion(slotId)
        val interactions = remember { MutableInteractionSource() }
        val hovered by interactions.collectIsHoveredAsState()
        val visual = visuals[slotId] ?: MenuSlotVisual()
        val state =
            MenuSlotState(
                visual.icon,
                visual.amount,
                visual.compactAmount,
                visual.marked,
                highlighted == slotId,
                hovered,
            )
        Box(modifier.then(region).hoverable(interactions)) { content(state) }
    }

    // Registers where the slot is laid out as its hit region, and leaves a focused text field when it is pressed.
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    private fun slotRegion(slotId: Int): Modifier {
        require(slotId >= 0 && slotId < slotCount)
        val token = remember(slotId) { Any() }
        val focus = LocalFocusManager.current
        DisposableEffect(slotId) {
            onDispose {
                synchronized(layoutLock) {
                    if (layout[slotId]?.first === token) {
                        layout.remove(slotId)
                        layoutRevision++
                    }
                }
            }
        }
        return Modifier.onGloballyPositioned { position ->
                val bounds = position.boundsInRoot()
                synchronized(layoutLock) {
                    if (layout[slotId] != (token to bounds)) {
                        layout[slotId] = token to bounds
                        layoutRevision++
                    }
                }
            }
            .onPointerEvent(PointerEventType.Press) { focus.clearFocus() }
    }

    internal fun viewport(metrics: ScreenMetrics) {
        checkOwner()
        if (this.metrics != metrics) reset()
        this.metrics = metrics
    }

    private fun point(x: Double, y: Double) = Offset(metrics.pixelX(x), metrics.pixelY(y))

    internal fun renderPoint(x: Double, y: Double): Offset =
        Offset(metrics.renderCoordinate(metrics.pixelX(x)), metrics.renderCoordinate(metrics.pixelY(y)))

    internal fun renderBounds(slotId: Int): MenuSlotBounds? =
        currentLayout[slotId]?.let { menuRenderBounds(it, metrics) }

    internal fun renderAreaBounds(): MenuSlotBounds? =
        synchronized(layoutLock) { area }?.let { menuRenderBounds(it, metrics) }

    fun slotAt(x: Double, y: Double): Int =
        currentLayout.entries.firstOrNull { it.value.contains(point(x, y)) }?.key ?: -1

    /** Clipped visible bounds in Minecraft GUI units, for integrations and input/accessibility hosts. */
    fun bounds(slotId: Int): MenuSlotBounds? =
        currentLayout[slotId]?.let {
            menuInputBounds(it, metrics)
        }

    fun areaBounds(): MenuSlotBounds? =
        synchronized(layoutLock) { area }
            ?.let {
                menuInputBounds(it, metrics)
            }

    internal fun move(x: Double, y: Double): Boolean {
        checkOwner()
        val hovered = if (blocked.get()) -1 else slotAt(x, y)
        ComposeThread.call { highlighted = hovered }
        return nativeActive
    }

    internal fun reset() {
        nativeActive = false
        nativeDragIds = emptySet()
    }

    internal fun nativeInteraction(active: Boolean, button: Int, ids: Set<Int>) {
        nativeActive = active
        nativeButton = button
        nativeDragIds = ids
    }

    private fun dragSlots() = nativeDragIds

    private fun dragButton() = nativeButton

    /** Also useful for keyboard bindings. Honors the same policy and executor as pointer gestures. */
    fun click(slotId: Int, button: Int, type: ClickType) {
        checkOwner()
        if (
            closed ||
                blocked.get() ||
                Minecraft.getInstance().player?.containerMenu !== menu ||
                slotId != -999 && slotId !in 0 until slotCount
        )
            return
        NativeSlotClicks.dispatch(
            slotId,
            button,
            type,
            adapter.behavior(menu.slots.getOrNull(slotId)),
            adapter::execute,
        ) { action ->
            adapter.localAction(menu.slots.getOrNull(slotId), action)
        }
    }

    internal fun refreshAfterLayout(): Boolean =
        if (synchronized(layoutLock) { loadedLayoutRevision != layoutRevision }) refresh() else false

    internal fun refresh(): Boolean {
        checkOwner()
        if (closed) return false
        val next =
            synchronized(layoutLock) {
                if (loadedLayoutRevision == layoutRevision) currentLayout
                else {
                    loadedLayoutRevision = layoutRevision
                    layout.mapValues { it.value.second }.filterValues { it.width > 0 && it.height > 0 }
                }
            }
        currentLayout = next
        val carried = menu.carried
        if (!ItemStack.isSameItemSameTags(cursor, carried)) {
            cursor = carried.copy()
            cursorIcon = if (carried.isEmpty) null else ItemIcon.snapshot(carried.copyWithCount(1))
        }
        val changes = mutableMapOf<Int, MenuSlotVisual>()
        for (id in currentLayout.keys) {
            val slot = menu.slots[id]
            val visual = adapter.visual(slot, snapshots[id])
            snapshots[id] = visual
            val preview =
                if (id in dragSlots() && dragSlots().size > 1) {
                    val amount = previewAmount(slot)
                    visual.copy(
                        icon = if (slot.item.isEmpty) cursorIcon else visual.icon,
                        amount = amount.toString(),
                        compactAmount = amount.toString(),
                    )
                } else visual
            if (shown[id] != preview) {
                shown[id] = preview
                changes[id] = preview
            }
        }
        val removed = shown.keys.filter { it !in currentLayout }
        removed.forEach {
            shown.remove(it)
            snapshots.remove(it)
        }
        val changed = changes.isNotEmpty() || removed.isNotEmpty()
        if (changed)
            ComposeThread.call {
                visuals.putAll(changes)
                removed.forEach { visuals.remove(it) }
            }
        return changed
    }

    private fun previewAmount(slot: Slot): Int {
        val carried = menu.carried
        val added =
            when (dragButton()) {
                1 -> 1
                2 -> carried.maxStackSize
                else -> carried.count / dragSlots().size.coerceAtLeast(1)
            }
        return minOf(slot.getMaxStackSize(carried), carried.maxStackSize, slot.item.count + added)
    }

    internal fun overlay(graphics: GuiGraphics, x: Int, y: Int, drawCursor: Boolean = true) {
        if (blocked.get()) return
        val mc = Minecraft.getInstance()
        val carried = menu.carried
        if (!carried.isEmpty) {
            if (!drawCursor) return
            val used =
                if (dragSlots().size > 1)
                    dragSlots().sumOf { id ->
                        (previewAmount(menu.slots[id]) - menu.slots[id].item.count).coerceAtLeast(0)
                    }
                else 0
            val remaining = (carried.count - used).coerceAtLeast(0)
            if (remaining > 0) {
                val image = carried.copyWithCount(remaining)
                graphics.pose().pushPose()
                try {
                    graphics.pose().translate(0f, 0f, 400f)
                    graphics.renderItem(image, x - 8, y - 8)
                    graphics.renderItemDecorations(mc.font, image, x - 8, y - 8)
                } finally {
                    graphics.pose().popPose()
                }
            }
        } else if (!interacting) {
            val id = slotAt(x.toDouble(), y.toDouble())
            val rendered = renderPoint(x.toDouble(), y.toDouble())
            menu.slots.getOrNull(id)?.let {
                adapter.tooltip(
                    graphics,
                    it,
                    kotlin.math.round(rendered.x).toInt(),
                    kotlin.math.round(rendered.y).toInt(),
                )
            }
        }
    }

    override fun close() {
        checkOwner()
        closed = true
        reset()
        snapshots.clear()
        shown.clear()
        cursorIcon = null
        synchronized(layoutLock) {
            layout.clear()
            area = null
        }
        ComposeThread.call { visuals.clear() }
    }

    internal fun detachLayout() {
        reset()
        synchronized(layoutLock) {
            layout.clear()
            area = null
            layoutRevision++
        }
        currentLayout = emptyMap()
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Menu input and snapshots must run on their creating game thread" }
    }
}
