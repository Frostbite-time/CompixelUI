package dev.compixel.render

import org.jetbrains.skia.Image

/**
 * GPU images a renderer has stopped using while other native owners may still reference them.
 *
 * Compose records images into pictures and display lists on its own thread, and each keeps a native reference after the
 * renderer lets go. Skia frees a GPU image on the thread that drops its last reference; without the GPU context there,
 * the texture is lost. A retired image therefore keeps the renderer's reference until no other owner is left and is
 * closed only then, so the final release happens on the renderer's thread.
 *
 * Not thread-safe: use it only on the thread that owns the GPU context.
 */
class RetiredImages {
    private val images = ArrayList<Image>()

    /** Retired images that are not closed yet. */
    val size: Int
        get() = images.size

    /** Takes over the caller's reference to [image]. */
    fun retire(image: Image) {
        if (!image.isClosed) images += image
    }

    /** Closes the retired images no other owner references any more. Call with the GPU context current. */
    fun release() {
        images.removeAll { image ->
            when {
                image.isClosed -> true
                // Nothing references a retired image anew, so a count of one is ours: this close is the final release.
                image.refCount == 1 -> {
                    image.close()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Closes every retired image when the renderer shuts down, before its GPU context. Returns how many were still
     * referenced elsewhere: those are released later on another thread, which can lose their GPU memory, so hosts
     * dispose of their UI before the renderer.
     */
    fun releaseAll(): Int {
        val shared = images.count { !it.isClosed && it.refCount > 1 }
        images.forEach(Image::close)
        images.clear()
        return shared
    }
}
