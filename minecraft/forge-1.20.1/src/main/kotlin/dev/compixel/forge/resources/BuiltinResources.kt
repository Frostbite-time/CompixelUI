package dev.compixel.forge.resources

import dev.compixel.ui.ore.internal.CompixelFont
import java.nio.file.Files
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraftforge.event.AddPackFindersEvent

/** Supplies the unpacked font through Minecraft's ordinary resource-pack stack. */
internal object BuiltinResources {
    fun register(event: AddPackFindersEvent) {
        if (event.packType != PackType.CLIENT_RESOURCES) return
        val directory = Minecraft.getInstance().gameDirectory.toPath().resolve(".cache/compixel/resources")
        CompixelFont.unpackTo(directory)
        val format = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES)
        Files.writeString(
            directory.resolve("pack.mcmeta"),
            """{"pack":{"description":"CompixelUI default resources","pack_format":$format}}""",
        )
        val supplier = Pack.ResourcesSupplier { id ->
            object : PathPackResources(id, directory, true) {
                override fun isHidden() = true
            }
        }
        val info = checkNotNull(Pack.readPackInfo("compixel:defaults", supplier))
        val pack =
            Pack.create(
                "compixel:defaults",
                Component.literal("CompixelUI defaults"),
                true,
                supplier,
                info,
                PackType.CLIENT_RESOURCES,
                Pack.Position.BOTTOM,
                true,
                PackSource.BUILT_IN,
            )
        event.addRepositorySource { output -> output.accept(pack) }
    }
}
