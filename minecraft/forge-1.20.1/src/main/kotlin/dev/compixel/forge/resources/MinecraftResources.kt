package dev.compixel.forge.resources

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import com.mojang.logging.LogUtils
import dev.compixel.forge.ClientThread
import dev.compixel.platform.ResourceSource
import dev.compixel.ui.resources.UiResources
import dev.compixel.ui.resources.fontResource as uiFontResource
import dev.compixel.ui.resources.painterResource as uiPainterResource
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
    uiPainterResource(location.toString(), filterQuality)

/** A TTF/OTF resource as a Compose FontFamily, updated automatically after resource reloads. */
@Composable
fun fontResource(path: String, fallback: FontFamily = FontFamily.Default): FontFamily =
    fontResource(resourceId(path), fallback)

/** A font at [location] in Minecraft's active resource packs. Missing or invalid fonts use [fallback]. */
@Composable
fun fontResource(location: ResourceLocation, fallback: FontFamily = FontFamily.Default): FontFamily =
    uiFontResource(location.toString(), fallback)

private fun resourceId(value: String): ResourceLocation {
    val separator = value.indexOf(':')
    val namespace = if (separator < 0) "minecraft" else value.substring(0, separator)
    val path = if (separator < 0) value else value.substring(separator + 1)
    return ResourceLocation(namespace, path)
}

/** Typed resources shared by the client's compositions, across screens and HUD layers. */
internal object MinecraftResources {
    private val logger = LogUtils.getLogger()

    val resources =
        UiResources(
            source =
                ResourceSource { path ->
                    Minecraft.getInstance().resourceManager.getResource(resourceId(path)).orElse(null)?.open()?.use {
                        it.readAllBytes()
                    }
                },
            thread = ClientThread,
            onError = { path, error -> logger.warn("Cannot load UI resource {}", path, error) },
        )
}
