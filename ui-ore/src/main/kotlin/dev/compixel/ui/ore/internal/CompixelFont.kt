package dev.compixel.ui.ore.internal

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.tukaani.xz.XZInputStream

/**
 * The xz-compressed Compixel font. Minecraft adapters unpack it into a built-in resource pack before resource loading.
 * Its bytes also serve desktop hosts and provide a fallback for invalid replacement fonts.
 */
object CompixelFont {
    const val RESOURCE_PATH = "compixel:font/compixel.ttf"

    private val bytes by lazy {
        checkNotNull(CompixelFont::class.java.getResourceAsStream("/dev/compixel/ui/ore/Compixel.ttf.xz")) {
                "The Compixel font resource is missing"
            }
            .let { XZInputStream(BufferedInputStream(it)) }
            .use { it.readBytes() }
    }

    internal val family by lazy { FontFamily(Font("compixel-bundled", ::data)) }

    /** Unpacks the font now. Adapters call this while the game loads, so that no screen waits for it. */
    @JvmStatic
    fun preload() {
        data()
    }

    /** A read-only stream of the bundled font, independent of any resource-pack replacement. */
    @JvmStatic fun open(): InputStream = ByteArrayInputStream(bytes)

    /** Writes the standard font resource inside a host's built-in resource pack and returns its file path. */
    @JvmStatic
    fun unpackTo(packRoot: Path): Path {
        val file = packRoot.resolve("assets/compixel/font/compixel.ttf")
        Files.createDirectories(file.parent)
        open().use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        return file
    }

    internal fun data(): ByteArray = bytes
}
