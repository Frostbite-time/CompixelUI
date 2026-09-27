package dev.composemc.forge.theme

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import dev.composemc.ui.ore.theme.OreThemeCatalog
import dev.composemc.ui.ore.theme.OreThemeId
import dev.composemc.ui.ore.theme.OreThemePatch
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/** Client-only IO prepares a complete snapshot; apply publishes it for the next Compose frame. */
internal object OreThemeReloadListener : SimplePreparableReloadListener<OreThemeCatalog>() {
    @Volatile
    var catalog: OreThemeCatalog = OreThemeCatalog.Default
        private set

    private val logger = LogUtils.getLogger()
    private const val DIRECTORY = "composemc/ore_themes"

    override fun prepare(manager: ResourceManager, profiler: ProfilerFiller): OreThemeCatalog {
        val layers = linkedMapOf<OreThemeId, MutableList<OreThemePatch>>()
        // Minecraft supplies each stack in increasing resource-pack priority.
        manager
            .listResourceStacks(DIRECTORY) { it.path.endsWith(".json") }
            .forEach { (location, stack) ->
                stack.forEach { resource ->
                    try {
                        val id =
                            OreThemeId(
                                location.namespace,
                                location.path.removePrefix("$DIRECTORY/").removeSuffix(".json"),
                            )
                        val root = resource.openAsReader().use { JsonParser.parseReader(it) }
                        require(root.isJsonObject) { "root: expected an object" }
                        val document = root.asJsonObject.entrySet().associate { (key, value) -> key to neutral(value) }
                        val patch = OreThemePatch.parse(document)
                        layers.getOrPut(id) { mutableListOf() }.add(patch)
                    } catch (error: Exception) {
                        logger.warn(
                            "Skipping Ore theme {} from pack {}: {}",
                            location,
                            resource.sourcePackId(),
                            error.message,
                        )
                    }
                }
            }
        return OreThemeCatalog.create(layers)
    }

    override fun apply(snapshot: OreThemeCatalog, manager: ResourceManager, profiler: ProfilerFiller) {
        if (catalog != snapshot) catalog = snapshot
    }

    private fun neutral(value: JsonElement): Any? =
        when {
            value.isJsonNull -> null
            value.isJsonObject -> value.asJsonObject.entrySet().associate { (key, child) -> key to neutral(child) }
            value.isJsonPrimitive ->
                with(value.asJsonPrimitive) {
                    when {
                        isString -> asString
                        isNumber -> asBigDecimal
                        isBoolean -> asBoolean
                        else -> null
                    }
                }
            else -> error("Arrays are not supported in theme files")
        }
}
