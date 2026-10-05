package dev.compixel.forge

import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.LayeredDraw
import net.minecraft.client.gui.screens.Screen

/**
 * Menu screens that another screen covers while their menu stays open, as a recipe viewer does. Each keeps its session
 * until it shows again; when its menu closes or the cover ends without it, the client tick releases it.
 */
internal object CoveredScreens {
    private class Covered(val screen: Screen, val menuOpen: () -> Boolean, val close: () -> Unit)

    private val covered = ArrayList<Covered>()

    fun add(screen: Screen, menuOpen: () -> Boolean, close: () -> Unit) {
        remove(screen)
        covered += Covered(screen, menuOpen, close)
    }

    fun remove(screen: Screen) {
        covered.removeAll { it.screen === screen }
    }

    /** Client tick: a covered screen whose menu closed, or that nothing covers any more, closes. */
    fun tick() {
        if (covered.isEmpty()) return
        val shown = Minecraft.getInstance().screen
        for (entry in covered.toList()) if (shown !== entry.screen && (shown == null || !entry.menuOpen())) {
            covered -= entry
            entry.close()
        }
    }

    /** The player left the world. */
    fun closeAll() {
        val all = covered.toList()
        covered.clear()
        all.forEach { it.close() }
    }
}

/**
 * Screens that closed for good while their content plays its ScreenTransition exit. They draw here, above the HUD and
 * beneath any open screen, without input, while the player has control again; each closes once its exit finished.
 */
internal object ScreenExits : LayeredDraw.Layer {
    private class Exit(val layer: ComposeLayer, val closed: () -> Unit) {
        var drawnAt = System.nanoTime()
        var finishing = false
    }

    private val exits = ArrayList<Exit>()

    /** Exits playing now. */
    val size: Int
        get() = exits.size

    /**
     * The host of [layer] closed for good: its content plays its exit here, or the layer closes now when it has none,
     * when no world is shown to draw it over, or while the game stops. [closed] runs once the layer has closed. A host
     * removed again while its exit plays keeps that exit.
     */
    fun close(layer: ComposeLayer, closed: () -> Unit = {}) {
        if (exits.any { it.layer === layer }) return
        val minecraft = Minecraft.getInstance()
        val playing =
            try {
                if (minecraft.isRunning && minecraft.level != null) layer.exit()
                else {
                    layer.close()
                    false
                }
            } catch (error: Throwable) {
                closed()
                throw error
            }
        if (playing) exits += Exit(layer, closed) else closed()
    }

    /** The host shows [layer] again before its exit finished: the exit ends now, and the host starts over. */
    fun reclaim(layer: ComposeLayer) {
        exits.firstOrNull { it.layer === layer }?.let(::finish)
    }

    override fun render(graphics: GuiGraphics, deltaTracker: DeltaTracker) {
        if (exits.isEmpty()) return
        val window = Minecraft.getInstance().window
        for (exit in exits.toList()) {
            if (exit.finishing) continue
            exit.layer.render(graphics, window.guiScaledWidth, window.guiScaledHeight)
            exit.drawnAt = System.nanoTime()
            // Closed at the start of the next frame, not while this frame still draws its images.
            if (exit.layer.exitFinished) {
                exit.finishing = true
                Minecraft.getInstance().tell { finish(exit) }
            }
        }
    }

    /** Client tick: an exit that could not draw for a while, for example without a world, ends now. */
    fun tick() {
        if (exits.isEmpty()) return
        val now = System.nanoTime()
        exits.filter { now - it.drawnAt > UNDRAWN_NANOS }.forEach(::finish)
    }

    /** The player left the world. */
    fun closeAll() = exits.toList().forEach(::finish)

    private fun finish(exit: Exit) {
        if (!exits.remove(exit)) return
        try {
            exit.layer.close()
        } finally {
            exit.closed()
        }
    }

    private const val UNDRAWN_NANOS = 250_000_000L
}
