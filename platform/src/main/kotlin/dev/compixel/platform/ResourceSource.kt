package dev.compixel.platform

/** Reads the highest-priority resource at a host's [path], or null when it is missing. Paths belong to the host. */
fun interface ResourceSource {
    fun read(path: String): ByteArray?
}
