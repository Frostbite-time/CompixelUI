package dev.compixel.development;

import dev.compixel.forge.sync.MenuAction;
import dev.compixel.forge.sync.MenuSync;
import dev.compixel.forge.sync.MinecraftSyncCodecs;
import dev.compixel.forge.sync.PacketCodec;
import dev.compixel.forge.sync.SyncedMenu;
import dev.compixel.sync.state.SyncCodec;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncSchema;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SyncAcceptanceMenu extends AbstractContainerMenu implements SyncedMenu {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, "compixel_development");
    public static final RegistryObject<MenuType<SyncAcceptanceMenu>> TYPE = MENUS.register(
            "sync_acceptance", () -> new MenuType<>(SyncAcceptanceMenu::new, FeatureFlags.DEFAULT_FLAGS));
    public static final String INITIAL = "initial-汉字-".repeat(5000);
    public static final String REPLACEMENT = "replacement-界面-".repeat(6000);
    // One shared native codec: the integrated server and the client both encode and decode with it.
    private static final SyncCodec<ItemStack> ITEM = MinecraftSyncCodecs.buffer(
            "compixel:item/1", 64 * 1024, PacketCodec.of(FriendlyByteBuf::writeItem, FriendlyByteBuf::readItem));
    public static final MenuAction<SyncAcceptanceMenu, ItemStack> GIVE =
            MenuAction.of("give", ITEM, (menu, player, value) -> {
                if (value.getItem() != Items.EMERALD || value.getCount() != 5) return false;
                menu.item = value;
                return true;
            });
    public static final MenuAction<SyncAcceptanceMenu, String> REPLACE =
            MenuAction.of("replace", SyncCodecs.string(256 * 1024), 256 * 1024, (menu, player, value) -> {
                if (!value.equals(REPLACEMENT)) return false;
                menu.text = value;
                menu.counter = 42;
                return true;
            });
    private static final SyncSchema<SyncAcceptanceMenu> SCHEMA = SyncSchema.<SyncAcceptanceMenu>builder(
                    "compixel:port_test", 1)
            .field("counter", SyncCodecs.INT, m -> m.counter, (m, v) -> m.counter = v)
            .field("text", SyncCodecs.string(256 * 1024), m -> m.text, (m, v) -> m.text = v)
            .field("item", ITEM, m -> m.item, (m, v) -> m.item = v)
            .build();
    public int counter;
    public String text = "";
    public ItemStack item = ItemStack.EMPTY;
    public final MenuSync<SyncAcceptanceMenu> sync =
            MenuSync.bind(this, SCHEMA).action(REPLACE).action(GIVE);

    public SyncAcceptanceMenu(int id, Inventory inventory) {
        super(TYPE.get(), id);
        if (!inventory.player.level().isClientSide) {
            counter = 7;
            text = INITIAL;
            item = new ItemStack(Items.DIAMOND, 3);
        }
    }

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }

    @Override
    public MenuSync<?> menuSync() {
        return sync;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
