package dev.composemc.testing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.composemc.bridge.ComposeThread
import dev.composemc.host.UiSession
import dev.composemc.platform.*
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.button.OreIconButton
import dev.composemc.ui.ore.input.OreIntField
import dev.composemc.ui.ore.input.OreSlider
import dev.composemc.ui.ore.input.OreTextField
import dev.composemc.ui.ore.inventory.OreSlot
import dev.composemc.ui.ore.selection.OreCheckbox
import dev.composemc.ui.ore.selection.OreSwitch
import dev.composemc.ui.ore.theme.OreFeedback
import dev.composemc.ui.ore.theme.OreTheme
import org.junit.jupiter.api.Test
import kotlin.test.*

class OreControlsTest {
    @Test fun `custom icon actions retain mouse keyboard feedback and disabled behavior in every style`() {
        for (style in OreButtonStyle.entries) {
            val enabled = mutableStateOf(true)
            var clicks = 0
            var feedback = 0
            Fixture { bound ->
                OreTheme(feedback = OreFeedback { feedback++ }) {
                    OreIconButton("Custom action", { clicks++ }, bound("custom"), enabled = enabled.value, style = style) { color ->
                        Canvas(Modifier.size(8.dp)) { drawRect(color) }
                    }
                    OreIconButton("Disabled", { fail("Disabled icon activated: $style") }, bound("disabled"), enabled = false, style = style) { color ->
                        Canvas(Modifier.size(8.dp)) { drawRect(color) }
                    }
                }
            }.use { fixture ->
                fixture.click("custom")
                fixture.key(UiKey.ENTER)
                assertEquals(2, clicks, "Mouse and keyboard: $style")
                assertEquals(2, feedback, "Exactly one feedback per activation: $style")
                ComposeThread.call { enabled.value = false }; fixture.frame()
                fixture.key(UiKey.ENTER)
                fixture.click("custom")
                fixture.click("disabled")
                assertEquals(2, clicks, "Disabling a focused icon must block activation: $style")
                assertEquals(2, feedback, "Disabled icons must stay silent: $style")
            }
        }
    }

    @Test fun `slot keeps its content geometry independent from its frame`() {
        var defaultContent = Rect.Zero
        var customContent = Rect.Zero
        Fixture { bound ->
            OreSlot(bound("default-slot").size(28.dp)) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { defaultContent = it.boundsInRoot() })
            }
            OreSlot(bound("custom-slot").size(40.dp), contentModifier = Modifier.size(24.dp)) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { customContent = it.boundsInRoot() })
            }
        }.use { fixture ->
            val defaultFrame = ComposeThread.call { fixture.bounds.getValue("default-slot") }
            val customFrame = ComposeThread.call { fixture.bounds.getValue("custom-slot") }
            val defaultArea = ComposeThread.call { defaultContent }
            val customArea = ComposeThread.call { customContent }
            assertEquals(28f, defaultFrame.width)
            assertEquals(40f, customFrame.width)
            assertEquals(16f, defaultArea.width)
            assertEquals(24f, customArea.width)
        }
    }

    @Test fun `integer editor preserves partial edits and saturates full signed range without overflow`() {
        val value = mutableIntStateOf(-5)
        Fixture { bound -> OreIntField(value.intValue, { value.intValue = it }, bound("number").width(300.dp)) }.use { fixture ->
            fixture.click("number")
            fixture.key(UiKey.A, Modifiers(control = true)); fixture.session.commitText("-"); fixture.frame()
            ComposeThread.call { value.intValue = 42 }; fixture.frame()
            fixture.session.commitText("7"); fixture.frame(); fixture.key(UiKey.ENTER)
            assertEquals(-7, value.intValue)
            val p = fixture.point("number")
            fixture.session.pointer(PointerInput(PointerAction.SCROLL, p.x, p.y, scrollY = -1f, modifiers = Modifiers(control = true)))
            fixture.frame(); assertEquals(93, value.intValue)
            ComposeThread.call { value.intValue = Int.MAX_VALUE - 1 }; fixture.frame()
            fixture.key(UiKey.UP, Modifiers(control = true)); assertEquals(Int.MAX_VALUE, value.intValue)
            ComposeThread.call { value.intValue = Int.MIN_VALUE + 1 }; fixture.frame()
            fixture.key(UiKey.DOWN, Modifiers(control = true)); assertEquals(Int.MIN_VALUE, value.intValue)
            fixture.session.pointer(PointerInput(PointerAction.PRESS, p.x + 129, p.y, MouseButton.LEFT))
            fixture.session.pointer(PointerInput(PointerAction.RELEASE, p.x + 129, p.y, MouseButton.LEFT))
            fixture.frame(); assertEquals(Int.MIN_VALUE + 1, value.intValue)
        }
    }

    @Test fun `integer editor commits complete values rejects overflow and bounds keyboard steps`() {
        val value = mutableIntStateOf(20)
        val enabled = mutableStateOf(true)
        val updates = mutableListOf<Int>()
        Fixture { bound ->
            OreIntField(value.intValue, { value.intValue = it; updates += it }, bound("number").width(300.dp), range = 0..100, enabled = enabled.value)
            OreButton("Other", {}, bound("other"))
        }.use { fixture ->
            fixture.click("number")
            fixture.key(UiKey.A, Modifiers(control = true))
            fixture.session.commitText("90"); fixture.frame()
            assertEquals(20, value.intValue)
            fixture.key(UiKey.ENTER)
            assertEquals(listOf(90), updates)
            fixture.key(UiKey.UP, Modifiers(control = true))
            assertEquals(100, value.intValue)
            fixture.key(UiKey.UP)
            assertEquals(listOf(90, 100), updates)
            fixture.key(UiKey.A, Modifiers(control = true)); fixture.session.commitText("2147483648"); fixture.frame()
            fixture.key(UiKey.ENTER)
            assertEquals(100, value.intValue)
            fixture.click("other")
            fixture.click("number")
            fixture.key(UiKey.DOWN, Modifiers(shift = true))
            assertEquals(90, value.intValue)
            ComposeThread.call { enabled.value = false }; fixture.frame()
            fixture.key(UiKey.DOWN)
            assertEquals(90, value.intValue)
        }
    }

    private class Fixture(content: @Composable (ModifierFactory) -> Unit) : AutoCloseable {
        val bounds = mutableMapOf<String, Rect>()
        var time = 1_000_000_000L
        val session = UiSession(Viewport(400, 350)) {
            OreTheme { Column(Modifier.fillMaxSize()) { content { id -> Modifier.onGloballyPositioned { bounds[id] = it.boundsInRoot() } } } }
        }
        init { frame() }
        fun frame() { time += 16_666_667; session.frame(time)?.close() }
        fun point(id: String) = ComposeThread.call { bounds.getValue(id).center }
        fun click(id: String) {
            val p = point(id)
            session.pointer(PointerInput(PointerAction.MOVE, p.x, p.y))
            session.pointer(PointerInput(PointerAction.PRESS, p.x, p.y, MouseButton.LEFT))
            session.pointer(PointerInput(PointerAction.RELEASE, p.x, p.y, MouseButton.LEFT))
            frame()
        }
        fun key(key: UiKey, modifiers: Modifiers = Modifiers()) {
            session.key(KeyInput(key, true, modifiers)); session.key(KeyInput(key, false, modifiers)); frame()
        }
        override fun close() = session.close()
    }

    @Test fun `buttons activate by mouse and keyboard while disabled buttons stay inactive`() {
        var clicks = 0
        var feedback = 0
        Fixture { bound ->
            OreTheme(feedback = OreFeedback { feedback++ }) {
                OreButton("Enabled", { clicks++ }, bound("enabled").width(200.dp))
                OreButton("Disabled", { clicks += 100 }, bound("disabled").width(200.dp), enabled = false)
            }
        }.use { fixture ->
            fixture.click("enabled")
            assertEquals(1, clicks)
            fixture.key(UiKey.ENTER)
            assertEquals(2, clicks)
            fixture.click("disabled")
            assertEquals(2, clicks)
            assertEquals(2, feedback)
        }
    }

    @Test fun `Ore field preserves text selection Unicode and focus semantics`() {
        val value = mutableStateOf("")
        Fixture { bound -> OreTextField(value.value, { value.value = it }, bound("field").width(300.dp), placeholder = "Name") }.use { fixture ->
            fixture.click("field")
            assertTrue(fixture.session.hasTextInputFocus)
            assertTrue(fixture.session.commitText("石英😀"))
            fixture.frame()
            assertEquals("石英😀", ComposeThread.call { value.value })
            fixture.key(UiKey.A, Modifiers(control = true))
            fixture.session.commitText("World")
            fixture.frame()
            assertEquals("World", ComposeThread.call { value.value })
            fixture.session.setFocused(false)
            assertFalse(fixture.session.commitText("ignored"))
        }
    }

    @Test fun `checkbox and switch retain toggle behavior and disabled state`() {
        val checked = mutableStateOf(false)
        val switched = mutableStateOf(false)
        Fixture { bound ->
            OreCheckbox(checked.value, { checked.value = it }, bound("check"), label = "Check")
            OreSwitch(switched.value, { switched.value = it }, bound("switch"))
            OreCheckbox(false, { fail("Disabled checkbox changed") }, bound("disabled"), enabled = false)
        }.use { fixture ->
            fixture.click("check")
            fixture.click("switch")
            fixture.click("disabled")
            assertTrue(ComposeThread.call { checked.value && switched.value })
        }
    }

    @Test fun `slider supports captured drag and disable without taking focus`() {
        val value = mutableFloatStateOf(.5f)
        val enabled = mutableStateOf(true)
        Fixture { bound -> OreSlider(value.floatValue, { value.floatValue = it }, bound("slider").width(300.dp), enabled = enabled.value) }.use { fixture ->
            val p = fixture.point("slider")
            fixture.session.pointer(PointerInput(PointerAction.PRESS, p.x, p.y, MouseButton.LEFT))
            fixture.session.pointer(PointerInput(PointerAction.MOVE, 500f, p.y))
            fixture.session.pointer(PointerInput(PointerAction.RELEASE, 500f, p.y, MouseButton.LEFT))
            fixture.frame()
            assertEquals(1f, ComposeThread.call { value.floatValue })
            assertFalse(fixture.session.hasTextInputFocus)
            ComposeThread.call { enabled.value = false }
            fixture.frame()
            fixture.click("slider")
            assertEquals(1f, ComposeThread.call { value.floatValue }, .001f)
        }
    }
}

private typealias ModifierFactory = (String) -> Modifier
