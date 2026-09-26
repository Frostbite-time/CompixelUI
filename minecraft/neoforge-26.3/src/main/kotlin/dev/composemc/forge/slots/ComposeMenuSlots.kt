package dev.composemc.forge.slots

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.composemc.bridge.ComposeThread
import dev.composemc.forge.slots.NativeSlotClicks
import dev.composemc.slots.*
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.inventory.OreSlot
import dev.composemc.ui.ore.theme.OreTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.resources.Identifier
import java.util.concurrent.atomic.AtomicBoolean
import dev.composemc.forge.item.IconRefresh
import dev.composemc.forge.item.ItemIcon
import dev.composemc.forge.item.MinecraftItemIcon

data class MenuSlotVisual(val icon: ItemIcon? = null, val amount: String = "", val marked: Boolean = false, val compactAmount: String = amount)
data class MenuSlotBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Public game-thread extension point for item/resource snapshots and the consumer's existing protocol. */
interface MenuSlotAdapter {
    fun visual(slot: Slot, previous: MenuSlotVisual?): MenuSlotVisual
    fun behavior(slot: Slot?): SlotBehavior = SlotBehavior.standard()
    fun canDragTo(slot: Slot): Boolean = true
    fun execute(slotId: Int, button: Int, type: ContainerInput)
    fun localAction(slot: Slot?, action: SlotIntent.Local) {}
    fun tooltip(graphics: GuiGraphicsExtractor, slot: Slot, x: Int, y: Int) {
        if (!slot.item.isEmpty) graphics.setTooltipForNextFrame(Minecraft.getInstance().font, slot.item, x, y)
    }
}

/** Default adapter for any ordinary vanilla-backed menu. It owns only immutable icon caches. */
open class VanillaMenuSlotAdapter(private val menu: AbstractContainerMenu) : MenuSlotAdapter {
    private val stacks = mutableMapOf<Int, ItemStack>()
    private val emptyIcons = mutableMapOf<Int, Pair<Identifier, ItemIcon>>()
    /** Empty armor/offhand and consumer template slots retain the native slot's atlas hint. */
    protected fun emptyIcon(slot: Slot): ItemIcon? {
        val sprite = slot.noItemIcon ?: run { emptyIcons.remove(slot.index); return null }
        emptyIcons[slot.index]?.takeIf { it.first == sprite }?.let { return it.second }
        val icon = ItemIcon.drawn("slot:$sprite", { graphics ->
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, 0, 0, 16, 16)
        }, IconRefresh.STATIC)
        emptyIcons[slot.index] = sprite to icon
        return icon
    }
    override fun visual(slot: Slot, previous: MenuSlotVisual?): MenuSlotVisual {
        val stack = slot.item
        if (stack.isEmpty) { stacks.remove(slot.index); return MenuSlotVisual(icon = emptyIcon(slot)) }
        val old = stacks[slot.index] ?: ItemStack.EMPTY
        if (previous != null && ItemStack.matches(old, stack)) return previous
        val copy = stack.copy(); stacks[slot.index] = copy
        if (previous?.icon != null && ItemStack.isSameItemSameComponents(old, copy))
            return previous.copy(amount = if (copy.count > 1) copy.count.toString() else "", compactAmount = if (copy.count > 1) copy.count.toString() else "")
        return MenuSlotVisual(ItemIcon.snapshot(copy.copyWithCount(1)), if (copy.count > 1) copy.count.toString() else "")
    }
    override fun execute(slotId: Int, button: Int, type: ContainerInput) {
        if ((menu as? dev.composemc.forge.sync.SyncedMenu)?.menuSync()?.isSendingAction() == true) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.containerMenu === menu) mc.gameMode?.handleContainerInput(menu.containerId, slotId, button, type, player)
    }
}

/** Compose placement is mirrored as clipped hit regions; all live menu access stays on the game thread. */
class ComposeMenuSlots<M : AbstractContainerMenu>(val menu: M, val adapter: MenuSlotAdapter = VanillaMenuSlotAdapter(menu)) : AutoCloseable {
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
    private var guiWidth = 1; private var guiHeight = 1; private var pixelWidth = 1; private var pixelHeight = 1
    private var cursor = ItemStack.EMPTY
    private var cursorIcon: ItemIcon? = null
    private var closed = false
    private var nativeActive = false
    private var nativeDragIds: Set<Int> = emptySet()
    private var nativeButton = 0
    private var highlighted by mutableStateOf(-1)
    val interacting get() = nativeActive
    val interactionsEnabled get() = !blocked.get()
    val visibleSlotIds: Set<Int> get() = currentLayout.keys
    @Composable
    fun Interaction(enabled: Boolean = true) { SideEffect { blocked.set(!enabled) } }

    fun areaModifier(): Modifier = Modifier.onGloballyPositioned { position -> synchronized(layoutLock) { area = position.boundsInRoot() } }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    fun Slot(slotId: Int, modifier: Modifier = Modifier) {
        require(slotId >= 0 && slotId < slotCount)
        val token = remember(slotId) { Any() }
        val focus = LocalFocusManager.current
        DisposableEffect(slotId) { onDispose { synchronized(layoutLock) { if (layout[slotId]?.first === token) { layout.remove(slotId); layoutRevision++ } } } }
        val visual = visuals[slotId] ?: MenuSlotVisual()
        OreSlot(modifier.onGloballyPositioned { position ->
            val bounds = position.boundsInRoot()
            synchronized(layoutLock) { if (layout[slotId] != (token to bounds)) { layout[slotId] = token to bounds; layoutRevision++ } }
        }.onPointerEvent(PointerEventType.Press) { focus.clearFocus() }, marked = visual.marked,
            highlighted = highlighted == slotId, contentModifier = Modifier.size(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                visual.icon?.let { MinecraftItemIcon(it, Modifier.fillMaxSize()) }
                val amount = if (maxWidth < 19.dp) visual.compactAmount else visual.amount
                // Amount labels formerly shared the adapter's 0.5 density. With one dp now
                // representing one Minecraft GUI unit, retain their intentionally compact
                // inventory treatment rather than doubling their visual footprint.
                val font = (minOf(12f, (maxWidth.value - 1) / (amount.length.coerceAtLeast(1) * 0.7f))
                    .coerceAtLeast(8f) / 2f).sp
                if (amount.isNotEmpty()) OreText(amount, Modifier.align(Alignment.BottomEnd), style = OreTheme.typography.caption.copy(fontSize = font), maxLines = 1)
            }
        }
    }

    internal fun viewport(guiWidth: Int, guiHeight: Int, pixelWidth: Int, pixelHeight: Int) {
        checkOwner()
        if (this.guiWidth != guiWidth || this.guiHeight != guiHeight || this.pixelWidth != pixelWidth || this.pixelHeight != pixelHeight) reset()
        this.guiWidth = guiWidth; this.guiHeight = guiHeight; this.pixelWidth = pixelWidth; this.pixelHeight = pixelHeight
    }
    private fun point(x: Double, y: Double) = Offset((x * pixelWidth / guiWidth).toFloat(), (y * pixelHeight / guiHeight).toFloat())
    fun slotAt(x: Double, y: Double): Int = currentLayout.entries.firstOrNull { it.value.contains(point(x, y)) }?.key ?: -1
    /** Clipped visible bounds in Minecraft GUI units, for integrations and input/accessibility hosts. */
    fun bounds(slotId: Int): MenuSlotBounds? = currentLayout[slotId]?.let {
        MenuSlotBounds(it.left.toDouble() * guiWidth / pixelWidth, it.top.toDouble() * guiHeight / pixelHeight,
            it.right.toDouble() * guiWidth / pixelWidth, it.bottom.toDouble() * guiHeight / pixelHeight)
    }
    fun areaBounds(): MenuSlotBounds? = synchronized(layoutLock) { area }?.let {
        MenuSlotBounds(it.left.toDouble() * guiWidth / pixelWidth, it.top.toDouble() * guiHeight / pixelHeight,
            it.right.toDouble() * guiWidth / pixelWidth, it.bottom.toDouble() * guiHeight / pixelHeight)
    }
    internal fun move(x: Double, y: Double): Boolean {
        checkOwner()
        val hovered = if (blocked.get()) -1 else slotAt(x, y)
        ComposeThread.call { highlighted = hovered }
        return nativeActive
    }
    internal fun reset() { nativeActive = false; nativeDragIds = emptySet() }
    internal fun nativeInteraction(active: Boolean, button: Int, ids: Set<Int>) {
        nativeActive = active; nativeButton = button; nativeDragIds = ids
    }
    private fun dragSlots() = nativeDragIds
    private fun dragButton() = nativeButton
    /** Also useful for keyboard bindings. Honors the same policy and executor as pointer gestures. */
    fun click(slotId: Int, button: Int, type: ContainerInput) {
        checkOwner()
        if (closed || blocked.get() || Minecraft.getInstance().player?.containerMenu !== menu || slotId != -999 && slotId !in 0 until slotCount) return
        NativeSlotClicks.dispatch(slotId, button, type, adapter.behavior(menu.slots.getOrNull(slotId)),
            adapter::execute) { action -> adapter.localAction(menu.slots.getOrNull(slotId), action) }
    }

    internal fun refreshAfterLayout(): Boolean =
        if (synchronized(layoutLock) { loadedLayoutRevision != layoutRevision }) refresh() else false

    internal fun refresh(): Boolean {
        checkOwner()
        if (closed) return false
        val next = synchronized(layoutLock) {
            if (loadedLayoutRevision == layoutRevision) currentLayout
            else { loadedLayoutRevision = layoutRevision; layout.mapValues { it.value.second }.filterValues { it.width > 0 && it.height > 0 } }
        }
        currentLayout = next
        val carried = menu.carried
        if (!ItemStack.isSameItemSameComponents(cursor, carried)) {
            cursor = carried.copy()
            cursorIcon = if (carried.isEmpty) null else ItemIcon.snapshot(carried.copyWithCount(1))
        }
        val changes = mutableMapOf<Int, MenuSlotVisual>()
        for (id in currentLayout.keys) {
            val slot = menu.slots[id]
            val visual = adapter.visual(slot, snapshots[id]); snapshots[id] = visual
            val preview = if (id in dragSlots() && dragSlots().size > 1) {
                val amount = previewAmount(slot)
                visual.copy(icon = if (slot.item.isEmpty) cursorIcon else visual.icon, amount = amount.toString(), compactAmount = amount.toString())
            } else visual
            if (shown[id] != preview) { shown[id] = preview; changes[id] = preview }
        }
        val removed = shown.keys.filter { it !in currentLayout }
        removed.forEach { shown.remove(it); snapshots.remove(it) }
        val changed = changes.isNotEmpty() || removed.isNotEmpty()
        if (changed) ComposeThread.call {
            visuals.putAll(changes); removed.forEach { visuals.remove(it) }
        }
        return changed
    }
    private fun previewAmount(slot: Slot): Int {
        val carried = menu.carried
        val added = when (dragButton()) { 1 -> 1; 2 -> carried.maxStackSize; else -> carried.count / dragSlots().size.coerceAtLeast(1) }
        return minOf(slot.getMaxStackSize(carried), carried.maxStackSize, slot.item.count + added)
    }
    internal fun overlay(graphics: GuiGraphicsExtractor, x: Int, y: Int, drawCursor: Boolean = true) {
        if (blocked.get()) return
        val mc = Minecraft.getInstance()
        val carried = menu.carried
        if (!carried.isEmpty) {
            if (!drawCursor) return
            val used = if (dragSlots().size > 1) dragSlots().sumOf { id -> (previewAmount(menu.slots[id]) - menu.slots[id].item.count).coerceAtLeast(0) } else 0
            val remaining = (carried.count - used).coerceAtLeast(0)
            if (remaining > 0) {
                val image = carried.copyWithCount(remaining)
                graphics.nextStratum()
                graphics.item(image, x - 8, y - 8)
                graphics.itemDecorations(mc.font, image, x - 8, y - 8)
            }
        } else if (!interacting) {
            val id = slotAt(x.toDouble(), y.toDouble())
            menu.slots.getOrNull(id)?.let { adapter.tooltip(graphics, it, x, y) }
        }
    }
    override fun close() {
        checkOwner()
        closed = true; reset(); snapshots.clear(); shown.clear(); cursorIcon = null
        synchronized(layoutLock) { layout.clear(); area = null }
        ComposeThread.call { visuals.clear() }
    }
    internal fun detachLayout() {
        reset()
        synchronized(layoutLock) { layout.clear(); area = null; layoutRevision++ }
        currentLayout = emptyMap()
    }
    private fun checkOwner() { check(Thread.currentThread() === owner) { "Menu input and snapshots must run on their creating game thread" } }
}
