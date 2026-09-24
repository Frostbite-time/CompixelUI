package dev.composemc.development;

import dev.composemc.neoforge.sync.MenuAction;
import dev.composemc.neoforge.sync.MenuSync;
import dev.composemc.neoforge.sync.SyncedMenu;
import dev.composemc.sync.SyncCodecs;
import dev.composemc.sync.SyncSchema;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class SyncAcceptanceMenu extends AbstractContainerMenu implements SyncedMenu {
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "composemc_development");
    public static final DeferredHolder<MenuType<?>, MenuType<SyncAcceptanceMenu>> TYPE = MENUS.register("sync_acceptance",
        () -> new MenuType<>(SyncAcceptanceMenu::new, FeatureFlags.DEFAULT_FLAGS));
    public static final String INITIAL = "initial-汉字-".repeat(5000);
    public static final String REPLACEMENT = "replacement-界面-".repeat(6000);
    public static final MenuAction<SyncAcceptanceMenu, String> REPLACE = MenuAction.of("replace",
        SyncCodecs.string(256 * 1024), 256 * 1024, (menu, player, value) -> {
            if (!value.equals(REPLACEMENT)) return false;
            menu.text = value; menu.counter = 42; return true;
        });
    private static final SyncSchema<SyncAcceptanceMenu> SCHEMA = SyncSchema.<SyncAcceptanceMenu>builder("composemc:port_test", 1)
        .field("counter", SyncCodecs.INT, m -> m.counter, (m,v) -> m.counter=v)
        .field("text", SyncCodecs.string(256 * 1024), m -> m.text, (m,v) -> m.text=v).build();
    public int counter;
    public String text = "";
    public final MenuSync<SyncAcceptanceMenu> sync = MenuSync.bind(this, SCHEMA).action(REPLACE);
    public SyncAcceptanceMenu(int id, Inventory inventory) {
        super(TYPE.get(), id);
        if (!inventory.player.level().isClientSide()) { counter=7; text=INITIAL; }
    }
    public static void register(IEventBus bus) { MENUS.register(bus); }
    @Override public MenuSync<?> menuSync() { return sync; }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
