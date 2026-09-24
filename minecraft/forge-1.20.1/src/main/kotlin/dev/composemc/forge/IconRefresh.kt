package dev.composemc.forge

import dev.composemc.bridge.NativeIconRefresh

/** Native icon invalidation policy. Only visible requested icons are refreshed, within the host budget. */
class IconRefresh private constructor(internal val kind: Kind, internal val millis: Long = 0) {
    internal enum class Kind { AUTO, STATIC, GAME_TICK, FRAME, INTERVAL }
    companion object {
        /** Inspect item models for animated sprites, overrides, glint and custom renderers. */
        @JvmField val AUTO = IconRefresh(Kind.AUTO)
        @JvmField val STATIC = IconRefresh(Kind.STATIC)
        /** Follows Minecraft texture ticks, including paused-world and frozen-tick behavior. */
        @JvmField val GAME_TICK = IconRefresh(Kind.GAME_TICK)
        @JvmField val FRAME = IconRefresh(Kind.FRAME)
        @JvmStatic fun every(millis: Long): IconRefresh {
            require(millis in 16..60_000) { "Icon refresh interval must be 16..60000 ms" }
            return IconRefresh(Kind.INTERVAL, millis)
        }
    }
}

/** The shared scheduler's form of a resolved policy. */
internal fun IconRefresh.scheduled(): NativeIconRefresh = when (kind) {
    IconRefresh.Kind.STATIC -> NativeIconRefresh.STATIC
    IconRefresh.Kind.GAME_TICK -> NativeIconRefresh.GAME_TICK
    IconRefresh.Kind.FRAME -> NativeIconRefresh.FRAME
    IconRefresh.Kind.INTERVAL -> NativeIconRefresh.every(millis)
    IconRefresh.Kind.AUTO -> error("Unresolved icon refresh policy")
}
