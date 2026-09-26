package dev.composemc.development

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.composemc.bridge.ComposeThread
import dev.composemc.forge.*
import dev.composemc.forge.item.IconRefresh
import dev.composemc.forge.item.ItemIcon
import dev.composemc.forge.item.MinecraftItemIcon
import dev.composemc.forge.item.MinecraftItemTooltip
import dev.composemc.forge.item.NativeItemOptions
import dev.composemc.testing.suite.BenchmarkCase
import dev.composemc.testing.suite.BenchmarkKind
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents

internal class BenchmarkModel {
    val preview = dev.composemc.demo.preview.DemoModel()
    var step by mutableIntStateOf(0)
    var tooltipTarget: Rect? = null
}

/** Fixed fixtures: no network, world simulation, random input, or dataset-sized snapshot allocation. */
internal class BenchmarkScreen
private constructor(
    val fixture: BenchmarkCase,
    val model: BenchmarkModel,
    icons: List<ItemIcon>,
    samples: Map<Int, ItemIcon> =
        if (
            fixture.kind == BenchmarkKind.ORE_COMPONENTS &&
                fixture.count == dev.composemc.demo.preview.DemoPage.Items.ordinal
        )
            listOf(0, 1, 3, 8).associateWith { ItemIcon.snapshot(icons[it].stack) }
        else emptyMap(),
) :
    ComposeScreen(
        Component.literal("Compose MC benchmark"),
        nativeItemOptions =
            if (fixture.kind == BenchmarkKind.NATIVE_ANIMATED) NativeItemOptions(cacheCapacity = 512)
            else NativeItemOptions(),
        content = { BenchmarkContent(fixture, model, icons, samples) },
    ) {
    private val componentExercise =
        if (fixture.kind == BenchmarkKind.ORE_COMPONENTS)
            dev.composemc.testing.ui.OreComponentExercise(
                model.preview,
                dev.composemc.demo.preview.DemoPage.entries[fixture.count],
            )
        else null

    fun verifyComponents() {
        componentExercise?.verify()
    }

    val componentsReady
        get() = componentExercise?.complete ?: true

    init {
        if (componentExercise != null)
            ComposeThread.call { model.preview.page = dev.composemc.demo.preview.DemoPage.entries[fixture.count] }
        // This packaged run has no consumer mod: verify the library supplies its own coordinate access.
        val slot = net.minecraft.world.inventory.Slot(net.minecraft.world.SimpleContainer(1), 0, 0, 0)
        slot.x = 7
        slot.y = 9
        check(slot.x == 7 && slot.y == 9) { "Native slot coordinate access was not installed" }
    }

    constructor(
        fixture: BenchmarkCase
    ) : this(
        fixture,
        ComposeThread.call { BenchmarkModel() },
        // Glint makes AUTO resolve to per-frame refresh, like enchanted items in real inventories.
        if (fixture.kind == BenchmarkKind.NATIVE_ANIMATED)
            benchmarkIcons(IconRefresh.AUTO) { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
        else if (
            fixture.kind in setOf(BenchmarkKind.NATIVE_STATIC, BenchmarkKind.NATIVE_SCROLL, BenchmarkKind.TOOLTIP) ||
                fixture.kind == BenchmarkKind.ORE_COMPONENTS &&
                    fixture.count in
                        setOf(
                            dev.composemc.demo.preview.DemoPage.Tooltips.ordinal,
                            dev.composemc.demo.preview.DemoPage.Slots.ordinal,
                            dev.composemc.demo.preview.DemoPage.Items.ordinal,
                        )
        )
            benchmarkIcons(if (fixture.kind == BenchmarkKind.NATIVE_STATIC) IconRefresh.STATIC else IconRefresh.AUTO)
        else emptyList(),
    )

    protected override fun isUiWindowFocused(): Boolean = SuiteEnvironment.uiFocused(super.isUiWindowFocused())

    fun advance() {
        componentExercise?.advance(checkNotNull(session))
        if (fixture.kind in setOf(BenchmarkKind.ANIMATION, BenchmarkKind.LIST, BenchmarkKind.NATIVE_SCROLL))
            session!!.post(Runnable { model.step++ })
    }
}

private fun benchmarkIcons(refresh: IconRefresh, configure: ItemStack.() -> Unit = {}): List<ItemIcon> =
    BuiltInRegistries.ITEM.asSequence()
        .filter { it !== Items.AIR }
        .take(255)
        .map { ItemIcon.snapshot(ItemStack(it).apply(configure), refresh) }
        .toList() +
        ItemIcon.snapshot(
            ItemStack(Items.BUNDLE).apply {
                set(
                    DataComponents.BUNDLE_CONTENTS,
                    BundleContents(
                        listOf(
                            ItemStackTemplate.fromNonEmptyStack(ItemStack(Items.DIAMOND, 16)),
                            ItemStackTemplate.fromNonEmptyStack(ItemStack(Items.APPLE, 8)),
                        )
                    ),
                )
                configure()
            }
        )

@Composable
private fun BenchmarkContent(
    fixture: BenchmarkCase,
    model: BenchmarkModel,
    icons: List<ItemIcon>,
    samples: Map<Int, ItemIcon>,
) {
    if (fixture.kind == BenchmarkKind.ORE_COMPONENTS) {
        dev.composemc.demo.preview.OreDemoScreen(
            model.preview,
            tooltipItem = { modifier ->
                if (icons.isNotEmpty()) MinecraftItemIcon(icons[0], modifier)
            },
            slotItem = { index, modifier ->
                if (icons.isNotEmpty()) MinecraftItemIcon(icons[index % icons.size], modifier)
            },
            nativeContent = { modifier ->
                dev.composemc.demo.preview.ItemBrowserDemo(
                    model.preview.itemBrowser,
                    icons.map { it.description },
                    modifier,
                    previewItem = { index, iconModifier ->
                        val icon = samples.getValue(index)
                        MinecraftItemTooltip(icon, iconModifier) { MinecraftItemIcon(icon, Modifier.fillMaxSize()) }
                    },
                ) { index, iconModifier ->
                    MinecraftItemTooltip(icons[index], iconModifier) {
                        MinecraftItemIcon(icons[index], Modifier.fillMaxSize())
                    }
                }
            },
        )
        return
    }

    val text = TextStyle(color = Color(0xFFE2E9E5), fontSize = 16.sp)
    Column(
        Modifier.fillMaxSize().background(Color(0xFF161D20)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Compose MC · ${fixture.name}", style = text.copy(fontSize = 24.sp))
        when (fixture.kind) {
            BenchmarkKind.ORE_COMPONENTS -> Unit
            BenchmarkKind.STATIC,
            BenchmarkKind.ANIMATION -> {
                repeat(8) { index ->
                    Row(
                        Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF242D32)).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(Color(0xFF9ED59B)))
                        BasicText("Setting $index — a stable Compose row", style = text)
                    }
                }
                Box(
                    Modifier.size(90.dp)
                        .graphicsLayer {
                            if (fixture.kind == BenchmarkKind.ANIMATION) {
                                rotationZ = (model.step % 360).toFloat()
                                translationX = (model.step % 240).toFloat()
                            }
                            alpha = 0.75f
                        }
                        .background(Color(0xFF8CCDDD))
                )
            }
            BenchmarkKind.LIST -> {
                val state = rememberLazyListState()
                LaunchedEffect(model.step) { state.scrollToItem(model.step % (fixture.count - 64)) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state) {
                    items(fixture.count, key = { it }) { index ->
                        Row(
                            Modifier.fillMaxWidth()
                                .height(16.dp)
                                .background(if (index % 2 == 0) Color(0xFF242D32) else Color(0xFF1B2428))
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                "Entry ${index.toString().padStart(6, '0')}",
                                style = text,
                                modifier = Modifier.weight(1f),
                            )
                            BasicText("${index * 37 % 4096}", style = text)
                        }
                    }
                }
            }
            BenchmarkKind.NATIVE_STATIC,
            BenchmarkKind.NATIVE_SCROLL -> {
                val state = rememberLazyGridState()
                if (fixture.kind == BenchmarkKind.NATIVE_SCROLL) {
                    LaunchedEffect(model.step) { state.scrollToItem((model.step / 4 * 12) % (fixture.count - 128)) }
                }
                LazyVerticalGrid(
                    GridCells.Fixed(12),
                    Modifier.fillMaxWidth().height(256.dp),
                    state = state,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(fixture.count, key = { it }) { index ->
                        Column(
                            Modifier.height(30.dp).background(Color(0xFF242D32)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            MinecraftItemIcon(icons[index % icons.size], Modifier.size(22.dp))
                            BasicText("#$index", style = text.copy(fontSize = 11.sp))
                        }
                    }
                }
            }
            // 256 distinct animated icons on one page; every one must get and refresh its image.
            BenchmarkKind.NATIVE_ANIMATED ->
                LazyVerticalGrid(
                    GridCells.Fixed(20),
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(fixture.count, key = { it }) { index ->
                        MinecraftItemIcon(icons[index % icons.size], Modifier.size(20.dp))
                    }
                }
            BenchmarkKind.TOOLTIP ->
                Box(Modifier.fillMaxSize()) {
                    BasicText("Native bundle tooltip", style = text)
                    MinecraftItemTooltip(
                        icons.last(),
                        Modifier.align(Alignment.BottomEnd).size(24.dp).onGloballyPositioned {
                            model.tooltipTarget = it.boundsInRoot()
                        },
                    ) {
                        MinecraftItemIcon(icons.last(), Modifier.fillMaxSize())
                    }
                }
        }
    }
}
