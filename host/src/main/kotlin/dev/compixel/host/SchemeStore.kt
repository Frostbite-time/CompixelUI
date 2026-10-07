package dev.compixel.host

import androidx.compose.ui.graphics.Color
import dev.compixel.ui.theme.ColorRole
import dev.compixel.ui.theme.SchemeCatalog
import dev.compixel.ui.theme.SchemeEditing
import dev.compixel.ui.theme.SchemeOwner
import dev.compixel.ui.theme.SchemeSettings
import dev.compixel.ui.theme.Schemes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The player's scheme choices and colors, which all hosts of a game share: each host shows [schemes] every frame, and
 * the color editor changes them as [SchemeEditing]. A change is saved through [save] shortly afterwards on a thread of
 * the store's own, or at once by [flush]. [load] and [save] exchange the settings as decoded JSON, null when there are
 * none yet; [warn] receives what cannot be read or written.
 */
class SchemeStore(
    private val load: () -> Map<String, Any?>?,
    private val save: (Map<String, Any?>) -> Unit,
    private val warn: (String) -> Unit,
) : SchemeEditing {
    private val lock = Any()
    private var unsaved: SchemeSettings? = null
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "CompixelUI color settings").apply { isDaemon = true }
        }
    }
    @Volatile private var shown = Schemes.Empty

    @Volatile
    var settings = SchemeSettings()
        private set

    /** Reads the saved settings; what cannot be read is reported and left out. */
    fun reload() {
        val document =
            try {
                load()
            } catch (error: Exception) {
                warn("Cannot read the color settings: $error")
                null
            }
        settings = document?.let { SchemeSettings.decode(it, warn) } ?: SchemeSettings()
    }

    /** [catalog]'s schemes with the player's choices: the same object while neither changes. */
    fun schemes(catalog: SchemeCatalog): Schemes {
        val current = shown
        val settings = settings
        if (current.catalog === catalog && current.settings === settings) return current
        return Schemes(catalog, settings).also { shown = it }
    }

    override fun select(owner: SchemeOwner, path: String?) = change(owner) { it.copy(scheme = path) }

    override fun edit(owner: SchemeOwner, path: String, role: ColorRole, color: Color?) =
        change(owner) { choice ->
            val colors =
                choice.edits[path].orEmpty().let { if (color == null) it - role.key else it + (role.key to color) }
            choice.copy(edits = if (colors.isEmpty()) choice.edits - path else choice.edits + (path to colors))
        }

    override fun restore(owner: SchemeOwner, path: String) = change(owner) { it.copy(edits = it.edits - path) }

    /** Saves at once what is not saved yet, for example as the color editor closes. */
    fun flush() {
        // Saving under the lock keeps the file in the order of the changes.
        synchronized(lock) {
            val pending = unsaved ?: return
            unsaved = null
            try {
                save(pending.encode())
            } catch (error: Exception) {
                warn("Cannot save the color settings: $error")
            }
        }
    }

    private fun change(owner: SchemeOwner, update: (SchemeSettings.Choice) -> SchemeSettings.Choice) {
        synchronized(lock) {
            val next = settings.with(owner, update(settings.choice(owner)))
            if (next == settings) return
            settings = next
            val scheduled = unsaved != null
            unsaved = next
            if (!scheduled) writer.schedule(::flush, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        // A drag through the color picker writes the file at most twice a second, not at every step.
        const val SAVE_DELAY_MILLIS = 500L
    }
}
