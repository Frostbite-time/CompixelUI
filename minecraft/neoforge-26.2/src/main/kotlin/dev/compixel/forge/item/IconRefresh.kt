package dev.compixel.forge.item

import dev.compixel.bridge.NativeIconRefresh

/** Native icon invalidation policy. Only visible requested icons are refreshed, within the host budget. */
class IconRefresh private constructor(internal val kind: Kind, internal val millis: Long = 0) {
    internal enum class Kind {
        AUTO,
        STATIC,
        GAME_TICK,
        FRAME,
        INTERVAL,
        ON_CHANGE,
    }

    companion object {
        /**
         * Inspect item models: glint and animated sprites refresh every game tick, and other items whenever their
         * resolved model or cooldown overlay changes.
         */
        @JvmField val AUTO = IconRefresh(Kind.AUTO)
        @JvmField val STATIC = IconRefresh(Kind.STATIC)
        /** Follows Minecraft texture ticks, including paused-world and frozen-tick behavior. */
        @JvmField val GAME_TICK = IconRefresh(Kind.GAME_TICK)
        /** Redraws every frame, within the frame budget; only for drawings that must move faster than game ticks. */
        @JvmField val FRAME = IconRefresh(Kind.FRAME)

        @JvmStatic
        fun every(millis: Long): IconRefresh {
            require(millis in 16..60_000) { "Icon refresh interval must be 16..60000 ms" }
            return IconRefresh(Kind.INTERVAL, millis)
        }

        /** AUTO's result for items redrawn only when their resolved model or cooldown overlay changes. */
        internal val ON_CHANGE = IconRefresh(Kind.ON_CHANGE)
    }
}

/** The shared scheduler's form of a resolved policy. */
internal fun IconRefresh.scheduled(): NativeIconRefresh =
    when (kind) {
        IconRefresh.Kind.STATIC -> NativeIconRefresh.STATIC
        IconRefresh.Kind.GAME_TICK -> NativeIconRefresh.GAME_TICK
        IconRefresh.Kind.FRAME -> NativeIconRefresh.FRAME
        IconRefresh.Kind.INTERVAL -> NativeIconRefresh.every(millis)
        IconRefresh.Kind.ON_CHANGE -> NativeIconRefresh.ON_CHANGE
        IconRefresh.Kind.AUTO -> error("Unresolved icon refresh policy")
    }
