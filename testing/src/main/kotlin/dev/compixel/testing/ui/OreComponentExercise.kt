package dev.compixel.testing.ui

import dev.compixel.bridge.ComposeThread
import dev.compixel.demo.preview.DemoModel
import dev.compixel.demo.preview.DemoPage
import dev.compixel.host.UiSession
import dev.compixel.platform.*

/** The actual F8 pages are also exercised from packaged, hidden GPU benchmark screens. */
val oreComponentPages =
    listOf(
        DemoPage.Selection,
        DemoPage.Menus,
        DemoPage.Numbers,
        DemoPage.Colors,
        DemoPage.Tree,
        DemoPage.Tooltips,
        DemoPage.Windows,
        DemoPage.Fields,
        DemoPage.Slots,
        DemoPage.Surfaces,
        DemoPage.Items,
    )

class OreComponentExercise(private val model: DemoModel, private val page: DemoPage) {
    private var frame = 0
    private var exercised = false
    private var tooltipStage = 0
    private var tooltipLock: TooltipLock? = null
    val complete
        get() = exercised

    fun advance(session: UiSession, nowNanos: Long = System.nanoTime()) {
        frame++
        fun hover(id: String) {
            val p = ComposeThread.call { checkNotNull(model.bounds[id]) { "Missing preview target $id" }.center }
            session.pointer(PointerInput(PointerAction.MOVE, p.x, p.y))
        }
        fun click(id: String, button: MouseButton = MouseButton.LEFT) {
            hover(id)
            val p = ComposeThread.call { model.bounds.getValue(id).center }
            session.pointer(PointerInput(PointerAction.PRESS, p.x, p.y, button))
            session.pointer(PointerInput(PointerAction.RELEASE, p.x, p.y, button))
        }
        fun key(key: UiKey, modifiers: Modifiers = Modifiers()) {
            session.key(KeyInput(key, true, modifiers))
            session.key(KeyInput(key, false, modifiers))
        }
        when (page) {
            DemoPage.Selection ->
                when (frame) {
                    20 -> click("choices-select")
                    40 -> {
                        key(UiKey.DOWN)
                        key(UiKey.ENTER)
                    }
                    60 -> {
                        click("mixed")
                        exercised = true
                    }
                }
            DemoPage.Menus ->
                when (frame) {
                    20 -> click("menu-area", MouseButton.RIGHT)
                    40 -> {
                        key(UiKey.DOWN)
                        key(UiKey.ENTER)
                        exercised = true
                    }
                    80 -> click("menu-button")
                }
            DemoPage.Numbers ->
                when (frame) {
                    20 -> {
                        click("long-field")
                        key(UiKey.A, Modifiers(control = true))
                        session.commitText("9223372036854775806")
                        key(UiKey.ENTER)
                    }
                    40 -> {
                        key(UiKey.UP)
                        exercised = true
                    }
                }
            DemoPage.Colors ->
                if (frame == 20) {
                    val bounds = ComposeThread.call { model.bounds.getValue("color-picker") }
                    val x = bounds.left + bounds.width * .3f
                    val y = bounds.top + bounds.width * .15f
                    session.pointer(PointerInput(PointerAction.MOVE, x, y))
                    session.pointer(PointerInput(PointerAction.PRESS, x, y, MouseButton.LEFT))
                    session.pointer(PointerInput(PointerAction.RELEASE, x, y, MouseButton.LEFT))
                    exercised = true
                }
            DemoPage.Tree ->
                when (frame) {
                    20 -> click("tree-world")
                    40 -> key(UiKey.LEFT)
                    60 -> {
                        key(UiKey.RIGHT)
                        exercised = true
                    }
                }
            // Each nested target lies inside the previous popup, which must lock before the pointer leaves its anchor.
            DemoPage.Tooltips ->
                if (frame >= 10 && tooltipStage < tooltipTargets.size && tooltipLock?.reached(nowNanos) != false) {
                    val target = tooltipTargets[tooltipStage++]
                    if (tooltipStage == tooltipTargets.size) {
                        click(target)
                        exercised = true
                        tooltipLock = null
                    } else {
                        hover(target)
                        tooltipLock = TooltipLock(session, nowNanos)
                    }
                }
            DemoPage.Windows ->
                if (frame == 20) {
                    click("window-field")
                    session.commitText("Window input")
                    exercised = true
                }
            DemoPage.Fields ->
                when (frame) {
                    20 -> click("field-error")
                    40 -> {
                        session.commitText("1")
                        exercised = true
                    }
                }
            DemoPage.Slots ->
                if (frame == 20) {
                    click("slots-next")
                    exercised = true
                }
            DemoPage.Surfaces ->
                if (frame == 20) {
                    exercised = true
                }
            DemoPage.Items ->
                when (frame) {
                    20 -> {
                        val p = ComposeThread.call { model.itemBrowser.visibleCells.getValue(0).center }
                        session.pointer(PointerInput(PointerAction.MOVE, p.x, p.y))
                        session.pointer(PointerInput(PointerAction.PRESS, p.x, p.y, MouseButton.LEFT))
                        session.pointer(PointerInput(PointerAction.RELEASE, p.x, p.y, MouseButton.LEFT))
                    }
                    40 -> ComposeThread.call { model.itemBrowser.scrollTarget = 120 }
                    60 -> {
                        exercised = true
                    }
                }
            else -> error("Not a component page: $page")
        }
    }

    fun verify() = ComposeThread.call {
        check(exercised) { "Component exercise did not complete: $page at frame $frame" }
        when (page) {
            DemoPage.Selection -> check(model.choice == 3 && model.mixedSelection.size == 3)
            DemoPage.Menus -> check(model.menuAction.isNotEmpty())
            DemoPage.Numbers -> check(model.longValue == Long.MAX_VALUE)
            DemoPage.Colors -> check(model.color != androidx.compose.ui.graphics.Color(0xCC3C8527))
            DemoPage.Tree -> check(model.treeSelection == "world" && "world" in model.expandedTree)
            DemoPage.Tooltips -> check(model.tooltipClicks == 1 && "tooltip-level-3" in model.bounds)
            DemoPage.Windows -> check(model.query == "Window input")
            DemoPage.Fields -> check(model.scrolledText == "1")
            DemoPage.Slots -> check(model.scrollPage == 1 && "slots-paged" in model.bounds)
            DemoPage.Surfaces -> check("surface-inset" in model.bounds && "surface-raised" in model.bounds)
            DemoPage.Items -> check(model.itemBrowser.selected == 0 && model.itemBrowser.firstVisible >= 120)
            else -> error("Not a component page")
        }
    }
}

private val tooltipTargets = listOf("tooltip-anchor", "tooltip-term", "tooltip-item", "tooltip-action")

/**
 * An Ore tooltip locks after a 600 ms progress animation that only advances on rendered frames, so a fixed delay fails
 * at low frame rates. Session commands run at the start of each frame; a marker command counts frames.
 */
private class TooltipLock(private val session: UiSession, private var postedAt: Long) {
    private val ran = java.util.concurrent.atomic.AtomicBoolean()
    private var frames = 0
    private var animatingBy = 0L
    private var lockedFrames = 0

    init {
        mark()
    }

    private fun mark() = check(session.post { ran.set(true) }) { "The session rejected a frame marker" }

    fun reached(nowNanos: Long): Boolean {
        if (ran.compareAndSet(true, false)) {
            // The popup composes on the first frame after the hover; its animation has started by the fourth.
            if (++frames == 4) animatingBy = nowNanos
            // A frame that starts after a later post runs at least that late; two such frames finish the lock.
            if (frames > 4 && postedAt - animatingBy >= 700_000_000L) lockedFrames++
            postedAt = nowNanos
            mark()
        }
        return lockedFrames >= 2
    }
}
