package dev.compixel.development

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.compixel.bridge.ComposeThread
import dev.compixel.demo.preview.DemoModel
import dev.compixel.demo.preview.ItemBrowserDemo
import dev.compixel.demo.preview.OreDemoScreen as DemoScreen
import dev.compixel.forge.*
import dev.compixel.forge.item.ItemIcon
import dev.compixel.forge.item.MinecraftItemIcon
import dev.compixel.forge.item.MinecraftItemTooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents

class ComposePreviewScreen
private constructor(
    parent: Screen?,
    internal val model: DemoModel,
    private val icons: List<ItemIcon>,
    private val samples: Map<Int, ItemIcon> =
        listOf(0, 1, 3, 8).filter { it < icons.size }.associateWith { ItemIcon.snapshot(icons[it].stack) },
) :
    ComposeScreen(
        Component.literal("CompixelUI"),
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
                    dev.compixel.ui.ore.display.OreText("Open a world to preview native items.", modifier)
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
    // Item defaults are supplied by a connected world's registry data.
    if (net.minecraft.client.Minecraft.getInstance().level == null) return emptyList()
    val examples =
        listOf(
            ItemIcon.snapshot(ItemStack(Items.STONE, 64)),
            ItemIcon.snapshot(ItemStack(Items.DIAMOND_SWORD).apply { damageValue = 900 }),
            ItemIcon.snapshot(
                ItemStack(Items.DIAMOND_SWORD).apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
            ),
            ItemIcon.snapshot(ItemStack(Items.CHEST)),
            ItemIcon.snapshot(ItemStack(Items.SHIELD)),
            ItemIcon.snapshot(ItemStack(Items.CLOCK)),
            ItemIcon.snapshot(ItemStack(Items.COMPASS)),
            ItemIcon.snapshot(ItemStack(Items.OAK_LEAVES)),
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
                }
            ),
        )
    return examples +
        BuiltInRegistries.ITEM.asSequence()
            .filter { it !== Items.AIR }
            .take(247)
            .map { ItemIcon.snapshot(ItemStack(it)) }
            .toList()
}
