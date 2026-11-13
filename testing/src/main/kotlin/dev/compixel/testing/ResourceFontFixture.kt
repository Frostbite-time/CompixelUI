package dev.compixel.testing

import dev.compixel.ui.ore.internal.CompixelFont
import java.nio.ByteBuffer

/** Resource-pack fixtures: the bundled font and a smaller copy, with valid SFNT checksums. */
object ResourceFontFixture {
    const val PATH = "compixel:font/compixel.ttf"

    val original: ByteArray by lazy {
        CompixelFont.open().use { it.readBytes() }
    }

    val smaller: ByteArray by lazy {
        val bytes = original.copyOf()
        val buffer = ByteBuffer.wrap(bytes)
        val headEntry =
            (0 until (buffer.getShort(4).toInt() and 0xFFFF))
                .map { 12 + it * 16 }
                .first { buffer.getInt(it) == 0x68656164 }
        val head = buffer.getInt(headEntry + 8)
        val units = buffer.getShort(head + 18).toInt() and 0xFFFF
        check(units in 16..8192)
        buffer.putShort(head + 18, (units * 2).toShort())
        buffer.putInt(head + 8, 0)
        fun checksum(start: Int, length: Int): Int {
            var sum = 0
            for (offset in 0 until length step 4) {
                var word = 0
                repeat(4) { byte ->
                    word =
                        (word shl 8) or if (offset + byte < length) bytes[start + offset + byte].toInt() and 255 else 0
                }
                sum += word
            }
            return sum
        }
        buffer.putInt(headEntry + 4, checksum(head, buffer.getInt(headEntry + 12)))
        buffer.putInt(head + 8, 0xB1B0AFBA.toInt() - checksum(0, bytes.size))
        bytes
    }
}
