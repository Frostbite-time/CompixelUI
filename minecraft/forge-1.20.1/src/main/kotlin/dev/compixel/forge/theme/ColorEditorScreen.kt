package dev.compixel.forge.theme

import androidx.compose.runtime.Composable
import com.mojang.logging.LogUtils
import dev.compixel.forge.ComposeScreen
import dev.compixel.ui.UiDesign
import dev.compixel.ui.ore.editor.OreColorEditor
import dev.compixel.ui.ore.editor.OreColorEditorText
import dev.compixel.ui.ore.editor.OreColorExport
import dev.compixel.ui.theme.SchemeOwner
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraftforge.fml.ModList

/**
 * CompixelUI's color editor for [design]'s owner, opened over [parent] and returning to it on the game thread. The
 * player chooses one of the owner's schemes and changes its colors while the design's preview shows them, and every
 * screen of the owner follows at once. The colors can be exported as a resource pack for other players or a modpack.
 * Content of a CompixelUI screen opens it through `LocalColorEditor`; other code can open this screen directly.
 */
class ColorEditorScreen(parent: Screen?, private val design: UiDesign) :
    ComposeScreen<Unit, Nothing>(Component.translatable("compixel.color_editor"), parent) {
    private val logger = LogUtils.getLogger()
    private val text = editorText(design.owner)
    private val preview = design.preview()
    private val packs: Path = Minecraft.getInstance().resourcePackDirectory
    private val packDescription = line("compixel.color_editor.pack")
    private val exported = line("compixel.color_editor.exported")
    private val failed = line("compixel.color_editor.export_failed")

    override fun snapshot() = Unit

    override fun handle(action: Nothing) = Unit

    @Composable
    override fun Content(state: Unit) =
        OreColorEditor(design.owner, ColorSchemes.store, text, ::export, ::requestClose) { design.Decorate(preview) }

    override fun removed() {
        try {
            super.removed()
        } finally {
            ColorSchemes.store.flush()
        }
    }

    /** Writes [request] as a resource pack folder; a few small files, written on the Compose thread. */
    internal fun export(request: OreColorExport): String =
        try {
            val owner = design.owner
            val schemes = ColorSchemes.current
            val newPath = if (request.asNew) unique(schemePath(request.name)) { it !in schemes.paths(owner) } else null
            val name = request.name.takeIf { request.asNew }
            val files = schemes.export(owner, request.scheme, newPath, name, request.makeDefault)
            val folder = packs.resolve(unique(folderName(request.name)) { !Files.exists(packs.resolve(it)) })
            val metadata =
                mapOf(
                    "pack_format" to SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES),
                    "description" to packDescription.format(request.name),
                )
            ColorSchemes.writeJson(folder.resolve("pack.mcmeta"), mapOf("pack" to metadata))
            for ((path, document) in files) ColorSchemes.writeJson(folder.resolve(path), document)
            exported.format(folder.fileName)
        } catch (error: Exception) {
            logger.warn("Cannot export colors", error)
            failed.format(error.message)
        }

    private companion object {
        fun line(key: String): String = Language.getInstance().getOrDefault(key)

        fun named(key: String, fallback: String): String = if (Language.getInstance().has(key)) line(key) else fallback

        /** The editor's words for [owner], read on the game thread. */
        fun editorText(owner: SchemeOwner): OreColorEditorText {
            val schemes = ColorSchemes.current
            val schema = owner.schema.name
            val mod =
                ModList.get()
                    .getModContainerById(owner.namespace)
                    .map { it.modInfo.displayName }
                    .orElse(owner.namespace)
            return OreColorEditorText(
                title = line("compixel.color_editor.title").format(mod),
                close = line("compixel.color_editor.close"),
                schemes =
                    schemes.paths(owner).associateWith { path ->
                        schemes.name(owner, path)?.let { named(it, it) } ?: path
                    },
                groups =
                    owner.schema.roles
                        .map { it.group }
                        .distinct()
                        .associateWith { named("color.$schema.group.$it", it) },
                colors = owner.schema.roles.associate { it.key to named("color.$schema.${it.key}", it.key) },
                derived = line("compixel.color_editor.derived"),
                follows = line("compixel.color_editor.follows"),
                restore = line("compixel.color_editor.restore"),
                restoreAll = line("compixel.color_editor.restore_all"),
                export = line("compixel.color_editor.export"),
                exportReplace = line("compixel.color_editor.export.replace"),
                exportNew = line("compixel.color_editor.export.new"),
                exportName = line("compixel.color_editor.export.name"),
                exportDefault = line("compixel.color_editor.export.default"),
                exportHint = line("compixel.color_editor.export.hint"),
                cancel = line("compixel.color_editor.cancel"),
                plane = line("compixel.color_editor.plane"),
                hue = line("compixel.color_editor.hue"),
                alpha = line("compixel.color_editor.alpha"),
            )
        }

        /** A scheme path from the player's name; names without Latin letters or digits become "custom". */
        fun schemePath(name: String): String =
            name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.-]+"), "_").trim('_', '.', '-').ifEmpty { "custom" }

        /** A folder name from the player's name, without the characters file systems refuse. */
        fun folderName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_").trim().trimEnd('.').ifEmpty { "colors" }

        /** [base], or the first of "base-2", "base-3"... that is [free]. */
        fun unique(base: String, free: (String) -> Boolean): String =
            if (free(base)) base else generateSequence(2) { it + 1 }.map { "$base-$it" }.first(free)
    }
}
