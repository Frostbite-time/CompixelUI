package dev.compixel.development

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.compixel.bridge.ComposeThread
import dev.compixel.forge.ComposeHudLayer
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.testing.suite.SuitePixels
import dev.compixel.ui.ore.display.OreProgressBar
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.layout.OreSurface
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

// Identical in every adapter; version differences belong in SuitePlatform.kt.

internal enum class SuiteHudMode {
    OFF,
    /** An opaque panel with a native item, for exact pixel checks. */
    ACCEPTANCE,
    /** A typical status panel that never changes. */
    STATIC,
    /** The status panel, changed before every frame. */
    ANIMATED,
}

internal class SuiteHudModel {
    var mode by mutableStateOf(SuiteHudMode.OFF)
    var icons by mutableStateOf(emptyList<ItemIcon>())
    var step by mutableIntStateOf(0)
    /** Pointer events that reached the content. A HUD layer takes no input, so this stays zero. */
    var pointerEvents = 0
    /** The game values the acceptance panel composed first and last in its session. */
    var firstValue = -1
    var value = -1
    /** The acceptance panel and its item in framebuffer pixels, as last laid out. */
    var panel: Rect? = null
    var item: Rect? = null
}

/**
 * The development HUD: one [ComposeHudLayer] whose content the suites choose, showing [gameValue] as its state. The
 * adapter registers it above every vanilla layer but draws it only while [enabled], so the other steps and cases run
 * without it. Game thread only.
 */
internal object SuiteHud {
    const val INSET_DP = 8
    const val PANEL_WIDTH_DP = 96
    const val PANEL_HEIGHT_DP = 32

    val model: SuiteHudModel by lazy { ComposeThread.call { SuiteHudModel() } }
    val layer: ComposeHudLayer<Int> by lazy {
        object : ComposeHudLayer<Int>() {
            override fun snapshot() = gameValue

            @Composable override fun Content(state: Int) = SuiteHudContent(model, state)
        }
    }
    var enabled = false
        private set

    /** The game state the HUD layer shows; it stays unchanged while benchmarks run. */
    var gameValue = 0

    private val items = listOf(Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE, Items.EMERALD)

    /** Draws [mode] in a fresh session; item snapshots are taken here, on the game thread. */
    fun show(mode: SuiteHudMode) {
        layer.close()
        val icons = items.map { ItemIcon.snapshot(ItemStack(it)) }
        ComposeThread.call {
            model.mode = mode
            model.icons = icons
            model.step = 0
            model.pointerEvents = 0
            model.firstValue = -1
            model.value = -1
            model.panel = null
            model.item = null
        }
        enabled = true
    }

    /** Stops drawing the HUD and releases its session. */
    fun hide() {
        enabled = false
        layer.close()
        ComposeThread.call { model.mode = SuiteHudMode.OFF }
    }

    /** Changes the content before the next drawn frame, as a benchmark step does for a screen. */
    fun advance() {
        val session = checkNotNull(layer.session) { "The development HUD has no session" }
        check(session.post(Runnable { model.step++ })) { "The development HUD session is closed or full" }
    }
}

@Composable
private fun SuiteHudContent(model: SuiteHudModel, value: Int) {
    val mode = model.mode
    if (mode == SuiteHudMode.OFF) return
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    model.pointerEvents++
                }
            }
        }
    ) {
        if (mode == SuiteHudMode.ACCEPTANCE) AcceptancePanel(model, value)
        else StatusPanel(model, animated = mode == SuiteHudMode.ANIMATED)
    }
}

@Composable
private fun AcceptancePanel(model: SuiteHudModel, value: Int) {
    SideEffect {
        if (model.firstValue < 0) model.firstValue = value
        model.value = value
    }
    Row(
        Modifier.padding(start = SuiteHud.INSET_DP.dp, top = SuiteHud.INSET_DP.dp)
            .size(SuiteHud.PANEL_WIDTH_DP.dp, SuiteHud.PANEL_HEIGHT_DP.dp)
            .background(Color(SuitePixels.HUD_ARGB))
            .onGloballyPositioned { model.panel = it.boundsInRoot() }
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinecraftItemIcon(
            model.icons.first(),
            Modifier.size(16.dp).onGloballyPositioned { model.item = it.boundsInRoot() },
        )
        BasicText("HUD ${model.step}", style = TextStyle(color = Color.White, fontSize = 8.sp))
    }
}

/** A typical HUD: Ore text, a progress bar and item icons. Only the animated variant reads [SuiteHudModel.step]. */
@Composable
private fun StatusPanel(model: SuiteHudModel, animated: Boolean) {
    val step = if (animated) model.step else 0
    OreSurface(Modifier.padding(SuiteHud.INSET_DP.dp).width(180.dp)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OreText("CompixelUI HUD")
            repeat(4) { row -> OreText("Status ${row + 1}: ${(step * (row + 3) + row * 250) % 1000}") }
            OreProgressBar(if (animated) step % 100 / 100f else 0.6f)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                model.icons.forEach { MinecraftItemIcon(it, Modifier.size(16.dp)) }
            }
        }
    }
}
