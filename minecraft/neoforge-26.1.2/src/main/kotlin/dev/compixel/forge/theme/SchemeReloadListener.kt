package dev.compixel.forge.theme

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import dev.compixel.ui.theme.SchemeCatalog
import dev.compixel.ui.theme.SchemeFile
import dev.compixel.ui.theme.SchemeId
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/**
 * Reads every resource pack's scheme files, `assets/<namespace>/compixel/schemes/<path>.json`, and owner indexes,
 * `assets/<namespace>/compixel/schemes.json`. Client-only IO prepares a complete catalog; apply publishes it for the
 * next frame of every host.
 */
internal object SchemeReloadListener : SimplePreparableReloadListener<SchemeCatalog>() {
    @Volatile
    var catalog: SchemeCatalog = SchemeCatalog.Empty
        private set

    private val logger = LogUtils.getLogger()
    private const val DIRECTORY = "compixel/schemes"
    private const val INDEX = "$DIRECTORY.json"

    override fun prepare(manager: ResourceManager, profiler: ProfilerFiller): SchemeCatalog {
        val schemes = linkedMapOf<SchemeId, MutableList<SchemeFile>>()
        val indexes = linkedMapOf<String, MutableList<SchemeFile>>()
        // Minecraft supplies each stack in increasing resource-pack priority.
        manager
            .listResourceStacks("compixel") {
                it.path == INDEX || it.path.startsWith("$DIRECTORY/") && it.path.endsWith(".json")
            }
            .forEach { (location, stack) ->
                val files = stack.mapNotNull { read(location.toString(), it) }
                if (location.path == INDEX) {
                    indexes.getOrPut(location.namespace) { mutableListOf() } += files
                    return@forEach
                }
                val path = location.path.removePrefix("$DIRECTORY/").removeSuffix(".json")
                val id = runCatching { SchemeId(location.namespace, path) }.getOrNull()
                if (id == null) logger.warn("Skipping scheme {}: invalid name", location)
                else schemes.getOrPut(id) { mutableListOf() } += files
            }
        return SchemeCatalog.create(schemes, indexes) { logger.warn("{}", it) }
    }

    override fun apply(snapshot: SchemeCatalog, manager: ResourceManager, profiler: ProfilerFiller) {
        if (catalog != snapshot) catalog = snapshot
    }

    private fun read(location: String, resource: Resource): SchemeFile? =
        try {
            val root = resource.openAsReader().use { JsonParser.parseReader(it) }
            require(root.isJsonObject) { "root: expected an object" }
            @Suppress("UNCHECKED_CAST")
            SchemeFile("$location in pack ${resource.sourcePackId()}", neutral(root) as Map<String, Any?>)
        } catch (error: Exception) {
            logger.warn("Skipping {} from pack {}: {}", location, resource.sourcePackId(), error.message)
            null
        }
}

/** A JSON value as maps, lists, strings, numbers, booleans and null. */
internal fun neutral(value: JsonElement): Any? =
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
