package dev.compixel.forge.resources

import dev.compixel.ui.ore.internal.CompixelFont
import java.nio.file.Files
import java.util.Optional
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.neoforged.neoforge.event.AddPackFindersEvent

/** Supplies the unpacked font through Minecraft's ordinary resource-pack stack. */
internal object BuiltinResources {
    fun register(event: AddPackFindersEvent) {
        if (event.packType != PackType.CLIENT_RESOURCES) return
        val directory = Minecraft.getInstance().gameDirectory.toPath().resolve(".cache/compixel/resources")
        CompixelFont.unpackTo(directory)
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES)
        Files.writeString(
            directory.resolve("pack.mcmeta"),
            """{"pack":{"description":"CompixelUI default resources","min_format":[${format.major()},${format.minor()}],"max_format":[${format.major()},${format.minor()}]}}""",
        )
        fun open(location: PackLocationInfo) =
            object : PathPackResources(location, directory) {
                override fun isHidden() = true
            }
        val supplier =
            object : Pack.ResourcesSupplier {
                override fun openPrimary(location: PackLocationInfo): PackResources = open(location)

                override fun openFull(location: PackLocationInfo, metadata: Pack.Metadata): PackResources =
                    open(location)
            }
        val location =
            PackLocationInfo(
                "compixel:defaults",
                Component.literal("CompixelUI defaults"),
                PackSource.BUILT_IN,
                Optional.empty(),
            )
        val pack =
            checkNotNull(
                Pack.readMetaAndCreate(
                    location,
                    supplier,
                    PackType.CLIENT_RESOURCES,
                    PackSelectionConfig(true, Pack.Position.BOTTOM, true),
                )
            )
        event.addRepositorySource { output -> output.accept(pack) }
    }
}
