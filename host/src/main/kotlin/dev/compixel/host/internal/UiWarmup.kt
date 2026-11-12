package dev.compixel.host.internal

/**
 * A host's warm-up: steps that prepare Compose, its design and the GPU before the game's first screen. Otherwise that
 * screen loads thousands of Compose and Skia classes, compiles its shaders and draws its first icons while the player
 * waits. The host adds the steps and calls [advance] on its UI thread once per frame it draws, except in frames a real
 * UI needs. Each call runs one step, so the work spreads over real frames and the GPU work of one frame is there for
 * the next, as it is for a real screen.
 *
 * A step that fails ends the warm-up: [failed] gets the error, the remaining steps are dropped, and those added with
 * `always` still run, to release what earlier steps opened.
 */
class UiWarmup(private val failed: (Throwable) -> Unit) {
    private class Step(val name: String, val always: Boolean, var attempts: Int, val run: () -> Boolean)

    private val steps = ArrayDeque<Step>()

    /** Whether every step ran or was dropped. */
    val finished
        get() = steps.isEmpty()

    /** The error that ended the warm-up early, if one did. */
    var failure: Throwable? = null
        private set

    /** Calls of [advance] that ran a step, the time they took, and the longest of them with its step's name. */
    var frames = 0
        private set

    var totalNanos = 0L
        private set

    var longestNanos = 0L
        private set

    var longestStep = ""
        private set

    /** Adds a step that runs once. With [always], it also runs after an earlier step failed. */
    fun step(name: String, always: Boolean = false, run: () -> Unit) {
        steps +=
            Step(name, always, 1) {
                run()
                true
            }
    }

    /** Adds a step that runs in as many frames as it takes to return true, [atMost] of them. */
    fun until(name: String, atMost: Int, run: () -> Boolean) {
        require(atMost > 0)
        steps += Step(name, false, atMost, run)
    }

    /** Runs the next step. False once none is left. */
    fun advance(): Boolean {
        val step = steps.firstOrNull() ?: return false
        val start = System.nanoTime()
        val done =
            try {
                step.run() || --step.attempts <= 0
            } catch (error: Throwable) {
                if (failure == null) {
                    failure = error
                    failed(error)
                }
                steps.removeAll { !it.always }
                true
            }
        if (done) steps.remove(step)
        val elapsed = System.nanoTime() - start
        frames++
        totalNanos += elapsed
        if (elapsed > longestNanos) {
            longestNanos = elapsed
            longestStep = step.name
        }
        return steps.isNotEmpty()
    }

    companion object {
        /** `-Dcompixel.warmup=false` turns the warm-up off. */
        val enabled
            get() = System.getProperty("compixel.warmup") != "false"
    }
}
