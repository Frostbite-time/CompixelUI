# HUD layers

[简体中文](../zh-CN/hud.md) · [Documentation](../README.md)

`ComposeHudLayer` draws Compose content as an ordinary HUD layer. Every target provides it in `dev.composemc.forge` with the same constructor. It implements that loader's own layer type, so you register and order it through the loader, like any other HUD layer. Examples here target NeoForge 1.21.1.

## Register a layer

Register from client-only code, for example the client entry point that registers your screens. The binding carries game state into the layer, as in the [quick start](getting-started.md#4-connect-game-state):

```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composemc.forge.ComposeHudLayer
import dev.composemc.host.UiBinding
import dev.composemc.ui.ore.display.OreText
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge

object HealthHud {
    private lateinit var health: UiBinding<Int, Nothing>

    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::registerLayer)
        NeoForge.EVENT_BUS.addListener(::tick)
    }

    // Runs on the client thread while the game starts.
    private fun registerLayer(event: RegisterGuiLayersEvent) {
        health = UiBinding(0)
        event.registerAbove(
            VanillaGuiLayers.HOTBAR,
            ResourceLocation.fromNamespaceAndPath("examplemod", "health"),
            ComposeHudLayer { OreText("Health: ${health.value}", Modifier.padding(8.dp)) },
        )
    }

    private fun tick(event: ClientTickEvent.Post) {
        Minecraft.getInstance().player?.let { health.update(it.health.toInt()) }
    }
}
```

| Target | Register in | Layer type | Identifiers and anchors |
| --- | --- | --- | --- |
| Forge 1.20.1 | `RegisterGuiOverlaysEvent` | `IGuiOverlay` | A `String` in your mod's namespace; `VanillaGuiOverlay.HOTBAR.id()` |
| NeoForge 1.21.1 | `RegisterGuiLayersEvent` | `LayeredDraw.Layer` | `ResourceLocation`; `VanillaGuiLayers` |
| NeoForge 26.x | `RegisterGuiLayersEvent` | `GuiLayer` | `Identifier`; `VanillaGuiLayers` |

## Behavior

- **Order.** The layer draws over the game view at the position you registered. Open screens draw above it, and it keeps drawing beneath them, as vanilla HUD elements do.
- **Hidden with the HUD.** Loaders can draw registered layers while F1 hides the vanilla HUD, so the layer checks this itself and draws nothing while the HUD is hidden.
- **No input.** Pointer, keys and text stay with the game or the open screen. The content never has window focus: text fields cannot be edited, Ore menus and tooltips do not open, and `MinecraftItemTooltip` never shows. Open a screen for interaction.
- **Native items.** `MinecraftItemIcon` works as in screens. Create `ItemIcon` snapshots on the client thread; see [native content](native-content.md).
- **Lifetime.** The session opens on the first frame drawn in a world. It keeps its state across window resizes, GUI scale changes and resource reloads, and closes when the player leaves the world. `close()` releases it sooner, for example when your mod turns the HUD off. The next drawn frame opens a new session, so `remember` state starts over.

## Cost

Every drawn frame costs one Compose-thread round trip and a full-window composite, even when nothing changed; unchanged content reuses its retained frame. Changed content records and redraws the frame. Each layer has its own session, window-sized surface and Skia context, so prefer one layer per mod with every element inside it, and avoid animations that never stop. The benchmark suite measures a static and an animated HUD; see [build and test](build-and-test.md#benchmark-protocol).

Read `rendererStatistics`, `nativeItemStatistics` and, with `-Dcomposemc.profile=true`, `frameProfiler` on the client thread.
