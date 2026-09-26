package dev.composemc.development

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal fun exampleBundle(): ItemStack =
    ItemStack(Items.BUNDLE).apply {
        orCreateTag.put(
            "Items",
            ListTag().apply {
                add(ItemStack(Items.DIAMOND, 16).save(CompoundTag()))
                add(ItemStack(Items.APPLE, 8).save(CompoundTag()))
            },
        )
    }
