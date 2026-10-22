package dev.compixel.ui.ore.internal

import java.io.BufferedInputStream
import org.tukaani.xz.XZInputStream

/**
 * The Compixel font, which ships xz-compressed. Compose asks for its bytes again whenever it reloads the typeface, so
 * they stay in memory once unpacked.
 */
object CompixelFont {
    private val bytes by lazy {
        checkNotNull(CompixelFont::class.java.getResourceAsStream("/dev/compixel/ui/ore/Compixel.ttf.xz")) {
                "The Compixel font resource is missing"
            }
            .let { XZInputStream(BufferedInputStream(it)) }
            .use { it.readBytes() }
    }

    /** Unpacks the font now. Adapters call this while the game loads, so that no screen waits for it. */
    @JvmStatic
    fun preload() {
        data()
    }

    internal fun data(): ByteArray = bytes
}
