package dev.compixel.ui.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.compixel.platform.ResourceSource
import dev.compixel.platform.UiThread
import java.io.FileNotFoundException

/** A reusable decoder. Its identity separates cached representations of the same resource path. */
fun interface ResourceType<T : Any> {
    fun decode(path: String, bytes: ByteArray): T
}

/**
 * A host's resource files, shared by its screens and HUD layers. Each path and [ResourceType] is decoded once while any
 * composition uses it. [reload] invalidates every type without replacing the compositions. Access belongs to [thread].
 * Decoded values must remain usable by their consumers after cache eviction; the cache does not close them.
 */
class UiResources(
    private val source: ResourceSource,
    private val thread: UiThread = UiThread.current(),
    private val onError: (String, Exception) -> Unit = { _, _ -> },
) {
    private data class Key(val path: String, val type: ResourceType<*>)

    private class Entry<T : Any>(val value: Result<T>, var users: Int = 0)

    private val entries = HashMap<Key, Entry<*>>()
    private val revision = mutableLongStateOf(0)

    /** Cached representations currently held by compositions, including failed loads. */
    val cachedResources: Int
        get() = entries.size

    /** The host successfully reloaded its resources; existing consumers read the new files on recomposition. */
    fun reload() {
        thread.check()
        entries.clear()
        revision.longValue++
    }

    /**
     * Reads and decodes [path], sharing the result with other users of [type]. Keep the decoder stable (an object or
     * remembered instance). Failed loads are reported once per cache entry and retried after a reload.
     */
    @Composable
    fun <T : Any> load(path: String, type: ResourceType<T>): Result<T> {
        val lease = remember(this, revision.longValue, path, type) { acquire(Key(path, type), type) }
        return lease.value
    }

    private fun <T : Any> acquire(key: Key, type: ResourceType<T>): Lease<T> {
        thread.check()
        @Suppress("UNCHECKED_CAST") val entry = entries.getOrPut(key) { Entry(decode(key.path, type)) } as Entry<T>
        entry.users++
        return Lease(key, entry)
    }

    private fun <T : Any> decode(path: String, type: ResourceType<T>): Result<T> =
        try {
            Result.success(type.decode(path, source.read(path) ?: throw FileNotFoundException(path)))
        } catch (error: Exception) {
            onError(path, error)
            Result.failure(error)
        }

    private inner class Lease<T : Any>(private val key: Key, private val entry: Entry<T>) : RememberObserver {
        val value: Result<T>
            get() = entry.value

        override fun onRemembered() = Unit

        override fun onForgotten() = release()

        override fun onAbandoned() = release()

        private fun release() {
            thread.check()
            if (--entry.users == 0 && entries[key] === entry) entries.remove(key)
        }
    }
}

/** The host's resources. Minecraft provides these automatically, including inside popups and dialogs. */
val LocalResources = staticCompositionLocalOf<UiResources?> { null }
