package dev.composemc.neoforge.slots;

import dev.composemc.slots.SlotTransferRoutes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Quick transfer for ordinary ItemStack-backed slots. Invoke from menu.quickMoveStack on the
 * owning game thread. Checks mayPickup/mayPlace and stack limits, merges before using empties,
 * and calls source onTake. Minecraft still owns prediction, validation and synchronization.
 * Specialized crafting, ghost/resource inventories and slots with nonstandard mutation semantics
 * provide their own executor and can reuse SlotTransferRoutes independently.
 */
public final class NativeSlotTransfers {
    private NativeSlotTransfers() {}

    public static ItemStack quickMove(AbstractContainerMenu menu, Player player, int sourceId, SlotTransferRoutes routes) {
        if (player.containerMenu != menu || !player.isAlive() || player.isSpectator()
                || !menu.stillValid(player) || sourceId < 0 || sourceId >= menu.slots.size())
            return ItemStack.EMPTY;
        Slot source = menu.slots.get(sourceId);
        // Partial recipe/trade output requires specialized settlement; fail closed before inserting.
        if (source instanceof ResultSlot || source instanceof MerchantResultSlot) return ItemStack.EMPTY;
        if (!source.mayPickup(player) || !source.hasItem()) return ItemStack.EMPTY;
        var targets = routes.targets(sourceId);
        // Validate the full route before any mutation, including use with a different menu layout.
        for (int id : targets) if (id < 0 || id >= menu.slots.size() || id == sourceId)
            throw new IllegalArgumentException("Route does not match this menu");
        ItemStack original = source.getItem().copy();
        ItemStack remaining = original.copy();
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int id : targets) {
                Slot target = menu.slots.get(id);
                // Two menu slots may alias one inventory location. Never insert into the source.
                if (target.container == source.container && target.getContainerSlot() == source.getContainerSlot()) continue;
                ItemStack current = target.getItem();
                if ((pass == 0) == current.isEmpty() || !target.mayPlace(remaining)) continue;
                if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, remaining)) continue;
                int room = Math.min(remaining.getMaxStackSize(), target.getMaxStackSize(remaining)) - current.getCount();
                int moved = Math.min(room, remaining.getCount());
                if (moved <= 0) continue;
                ItemStack replacement = remaining.copyWithCount(current.getCount() + moved);
                remaining.shrink(moved);
                // Standard Slot setters and hooks follow the same assumptions as vanilla quickMoveStack.
                target.setByPlayer(replacement);
                if (remaining.isEmpty()) break;
            }
        }
        int moved = original.getCount() - remaining.getCount();
        if (moved == 0) return ItemStack.EMPTY;
        source.setByPlayer(remaining);
        source.onQuickCraft(remaining, original);
        source.onTake(player, original.copyWithCount(moved));
        return original;
    }
}
