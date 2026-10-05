package dev.compixel.forge.theme

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import dev.compixel.ui.theme.ThemeCatalog
import dev.compixel.ui.theme.ThemeId
import dev.compixel.ui.theme.ThemeLayer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/**
 * Reads every resource pack's theme files, `assets/<namespace>/compixel/themes/<path>.json`. Client-only IO prepares a
 * complete catalog; apply publishes it for the next Compose frame. Design systems parse their own sections.
 */
internal object ThemeReloadListener : SimplePreparableReloadListener<ThemeCatalog>() {
    @Volatile
    var catalog: ThemeCatalog = ThemeCatalog.Empty
        private set

    private val logger = LogUtils.getLogger()
    private const val DIRECTORY = "compixel/themes"

    override fun prepare(manager: ResourceManager, profiler: ProfilerFiller): ThemeCatalog {
        val layers = linkedMapOf<ThemeId, MutableList<ThemeLayer>>()
        // Minecraft supplies each stack in increasing resource-pack priority.
        manager
            .listResourceStacks(DIRECTORY) { it.path.endsWith(".json") }
            .forEach { (location, stack) ->
                stack.forEach { resource ->
                    try {
                        val id =
                            ThemeId(
                                location.namespace,
                                location.path.removePrefix("$DIRECTORY/").removeSuffix(".json"),
                            )
                        val root = resource.openAsReader().use { JsonParser.parseReader(it) }
                        require(root.isJsonObject) { "root: expected an object" }
                        val document = root.asJsonObject.entrySet().associate { (key, value) -> key to neutral(value) }
                        val source = "$location in pack ${resource.sourcePackId()}"
                        layers.getOrPut(id) { mutableListOf() }.add(ThemeLayer(source, document))
                    } catch (error: Exception) {
                        logger.warn(
                            "Skipping theme {} from pack {}: {}",
                            location,
                            resource.sourcePackId(),
                            error.message,
                        )
                    }
                }
            }
        return ThemeCatalog.create(layers) { logger.warn("{}", it) }
    }

    override fun apply(snapshot: ThemeCatalog, manager: ResourceManager, profiler: ProfilerFiller) {
        if (catalog != snapshot) catalog = snapshot
    }

    private fun neutral(value: JsonElement): Any? =
        when {
            value.isJsonNull -> null
            value.isJsonObject -> value.asJsonObject.entrySet().associate { (key, child) -> key to neutral(child) }
            value.isJsonArray -> value.asJsonArray.map(::neutral)
            else ->
                with(value.asJsonPrimitive) {
                    when {
                        isString -> asString
                        isNumber -> asBigDecimal
                        isBoolean -> asBoolean
                        else -> null
                    }
                }
        }
}
