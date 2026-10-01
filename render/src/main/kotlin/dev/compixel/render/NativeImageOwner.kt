package dev.compixel.render

import org.jetbrains.skia.Image

/** Owner-thread reference to a published native image. Retirement always goes through its renderer. */
class NativeImageOwner(private val retire: (Image) -> Unit) : AutoCloseable {
    var image: Image? = null
        private set

    fun replace(next: Image?) {
        if (image === next) return
        image?.let(retire)
        image = next
    }

    override fun close() = replace(null)
}
