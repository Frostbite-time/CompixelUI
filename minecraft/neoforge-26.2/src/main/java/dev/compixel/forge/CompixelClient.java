package dev.compixel.forge;

import dev.compixel.platform.internal.ComposeRuntime;
import dev.compixel.platform.internal.KotlinRuntime;
import dev.compixel.platform.internal.SkikoNatives;
import dev.compixel.ui.ore.internal.CompixelFont;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;

@Mod(value = CompixelClient.MOD_ID, dist = Dist.CLIENT)
public final class CompixelClient {
    public static final String MOD_ID = "compixel";

    public CompixelClient(IEventBus modEventBus, ModContainer container) {
        var kotlinIssue = KotlinRuntime.check();
        if (kotlinIssue != null) {
            modEventBus.addListener((FMLConstructModEvent event) -> event.enqueueWork(() -> {
                throw new ModLoadingException(ModLoadingIssue.error("{0}", kotlinIssue.displayMessage())
                        .withAffectedMod(container.getModInfo())
                        .withCause(kotlinIssue.cause()));
            }));
            return;
        }
        ComposeRuntime.requireAvailable();
        SkikoNatives.install();
        CompixelFont.preload();
        ClientBootstrap.INSTANCE.register(modEventBus, container);
    }
}
