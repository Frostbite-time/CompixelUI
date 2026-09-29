package dev.compixel.forge.input

import dev.compixel.platform.ClipboardPort
import java.util.concurrent.atomic.AtomicReference

/** The EDT only accesses snapshots; GLFW remains on Minecraft's thread. */
internal class ClipboardMailbox : ClipboardPort {
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
