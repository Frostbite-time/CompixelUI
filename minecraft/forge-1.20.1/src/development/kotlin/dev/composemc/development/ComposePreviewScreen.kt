package dev.composemc.development

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.composemc.bridge.ComposeThread
import dev.composemc.demo.preview.DemoModel
import dev.composemc.demo.preview.ItemBrowserDemo
import dev.composemc.demo.preview.OreDemoScreen as DemoScreen
import dev.composemc.forge.*
import dev.composemc.forge.item.ItemIcon
import dev.composemc.forge.item.MinecraftItemIcon
import dev.composemc.forge.item.MinecraftItemTooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class ComposePreviewScreen
private constructor(
    parent: Screen?,
    internal val model: DemoModel,
    private val icons: List<ItemIcon>,
    private val samples: Map<Int, ItemIcon> =
        listOf(0, 1, 3, 8).filter { it < icons.size }.associateWith { ItemIcon.snapshot(icons[it].stack) },
) :
    ComposeScreen(
        Component.literal("Compose MC"),
        parent,
        content = {
            DemoScreen(
                model,
                tooltipItem =
                    if (icons.isEmpty()) null
                    else
                        { modifier ->
                            MinecraftItemIcon(icons[0], modifier)
                        },
                slotItem =
                    if (icons.isEmpty()) null
                    else
                        { index, modifier ->
                            MinecraftItemIcon(icons[index % icons.size], modifier)
                        },
            ) { modifier ->
                if (icons.isEmpty())
                    dev.composemc.ui.ore.display.OreText("Open a world to preview native items.", modifier)
                else
                    ItemBrowserDemo(
                        model.itemBrowser,
                        icons.map { it.description },
                        modifier,
                        previewItem = { index, itemModifier ->
                            val icon = samples.getValue(index)
                            MinecraftItemTooltip(icon, itemModifier) { MinecraftItemIcon(icon, Modifier.fillMaxSize()) }
                        },
                    ) { index, itemModifier ->
                        MinecraftItemTooltip(icons[index], itemModifier) {
                            MinecraftItemIcon(icons[index], Modifier.fillMaxSize())
                        }
                    }
            }
        },
    ) {
    internal val itemBrowser
        get() = model.itemBrowser

    constructor(
        parent: Screen? = null
    ) : this(
        parent,
        ComposeThread.call { DemoModel() },
        previewIcons(),
    )

    // Suites drive hover and input through Screen callbacks with logical focus, never OS focus.
    protected override fun isUiWindowFocused(): Boolean = SuiteEnvironment.uiFocused(super.isUiWindowFocused())
}

private fun previewIcons(): List<ItemIcon> {
    val examples =
        listOf(
            ItemIcon.snapshot(ItemStack(Items.STONE, 64)),
            ItemIcon.snapshot(ItemStack(Items.DIAMOND_SWORD).apply { damageValue = 900 }),
            ItemIcon.snapshot(
                ItemStack(Items.DIAMOND_SWORD).apply {
                    enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 1)
                }
            ),
            ItemIcon.snapshot(ItemStack(Items.CHEST)),
            ItemIcon.snapshot(ItemStack(Items.SHIELD)),
            ItemIcon.snapshot(ItemStack(Items.CLOCK)),
            ItemIcon.snapshot(ItemStack(Items.COMPASS)),
            ItemIcon.snapshot(ItemStack(Items.OAK_LEAVES)),
            ItemIcon.snapshot(exampleBundle()),
        )
    return examples +
        BuiltInRegistries.ITEM.asSequence()
            .filter { it !== Items.AIR }
            .take(247)
            .map { ItemIcon.snapshot(ItemStack(it)) }
            .toList()
}
