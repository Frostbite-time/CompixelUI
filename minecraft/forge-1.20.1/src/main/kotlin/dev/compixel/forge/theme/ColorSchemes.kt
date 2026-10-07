package dev.compixel.forge.theme

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import dev.compixel.host.SchemeStore
import dev.compixel.ui.theme.Schemes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import net.minecraftforge.fml.loading.FMLPaths

/**
 * The player's color choices, kept in `config/compixel-colors.json`, and the schemes every host shows: the scheme files
 * of the current resources with the player's choices on top.
 */
internal object ColorSchemes {
    private val logger = LogUtils.getLogger()
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    val store = SchemeStore(::read, ::write) { logger.warn("{}", it) }

    val current: Schemes
        get() = store.schemes(SchemeReloadListener.catalog)

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve("compixel-colors.json")

    @Suppress("UNCHECKED_CAST")
    private fun read(): Map<String, Any?>? =
        file.takeIf(Files::exists)?.let { path ->
            Files.newBufferedReader(path).use { neutral(JsonParser.parseReader(it)) as? Map<String, Any?> }
        }

    private fun write(document: Map<String, Any?>) = writeJson(file, document)

    /** Writes [document] to [path] in one step, so a failed write leaves the previous file whole. */
    fun writeJson(path: Path, document: Map<String, Any?>) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, gson.toJson(document))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
