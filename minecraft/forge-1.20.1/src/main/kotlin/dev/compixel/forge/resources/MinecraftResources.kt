package dev.compixel.forge.resources

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import com.mojang.logging.LogUtils
import dev.compixel.forge.ClientThread
import dev.compixel.host.resources.UiResources
import dev.compixel.host.resources.painterResource as hostPainterResource
import dev.compixel.platform.ResourceSource
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation

/**
 * An image from Minecraft's active resource packs, directly usable by Compose Image or Modifier.paint. Reloading
 * resources updates it in place. Use a full file path such as `example:textures/gui/panel.png`; an omitted namespace
 * means `minecraft`. Pixel art uses nearest-neighbour sampling unless [filterQuality] says otherwise.
 */
@Composable
fun painterResource(path: String, filterQuality: FilterQuality = FilterQuality.None): Painter =
    painterResource(resourceId(path), filterQuality)

/** An image at [location] in Minecraft's active resource packs; see [painterResource]. */
@Composable
fun painterResource(location: ResourceLocation, filterQuality: FilterQuality = FilterQuality.None): Painter =
    hostPainterResource(location.toString(), filterQuality)

private fun resourceId(value: String): ResourceLocation {
    val separator = value.indexOf(':')
    val namespace = if (separator < 0) "minecraft" else value.substring(0, separator)
    val path = if (separator < 0) value else value.substring(separator + 1)
    return ResourceLocation(namespace, path)
}

/** One decoded copy per image used by the client's compositions, across screens and HUD layers. */
internal object MinecraftResources {
    private val logger = LogUtils.getLogger()

    val images =
        UiResources(
            source =
                ResourceSource { path ->
                    Minecraft.getInstance().resourceManager.getResource(resourceId(path)).orElse(null)?.open()?.use {
                        it.readAllBytes()
                    }
                },
            thread = ClientThread,
            onError = { path, error -> logger.warn("Cannot load UI image {}", path, error) },
        )
}
