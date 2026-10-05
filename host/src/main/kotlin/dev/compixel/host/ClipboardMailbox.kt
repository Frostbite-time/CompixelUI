package dev.compixel.host

import dev.compixel.platform.ClipboardPort
import java.util.concurrent.atomic.AtomicReference

/**
 * The clipboard as Compose sees it. Compose reads and writes snapshots on its own thread, while the host keeps the
 * system clipboard on the game thread: it [refresh]es the snapshot before input and copies [takeWrite] out afterwards.
 */
class ClipboardMailbox : ClipboardPort {
    private val snapshot = AtomicReference("")
    private val pendingWrite = AtomicReference<String?>()

    override fun readText(): String = pendingWrite.get() ?: snapshot.get()

    override fun writeText(text: String) {
        pendingWrite.set(text)
    }

    fun refresh(text: String) {
        snapshot.set(text)
    }

    fun takeWrite(): String? = pendingWrite.getAndSet(null)?.also(snapshot::set)
}
