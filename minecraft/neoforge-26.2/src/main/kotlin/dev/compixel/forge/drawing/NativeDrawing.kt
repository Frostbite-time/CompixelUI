package dev.compixel.forge.drawing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.mojang.blaze3d.systems.RenderSystem
import dev.compixel.bridge.NativeImageAtlas
import dev.compixel.bridge.NativeImageContent
import dev.compixel.bridge.NativeImageMailbox
import dev.compixel.bridge.NativeImageRefresh
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Immutable handle created on the game thread. Its callback runs only on the game/render thread. */
class NativeDrawing
private constructor(
    internal val id: Long,
    val description: String,
    val refresh: NativeRefresh,
    internal val drawing: Consumer<NativeDrawingContext>,
) {
    companion object {
        private val ids = AtomicLong()

        /** Reuse the handle while its captured content is unchanged; create a new handle for new snapshots. */
        @JvmStatic
        @JvmOverloads
        fun create(
            description: String,
            drawing: Consumer<NativeDrawingContext>,
            refresh: NativeRefresh = NativeRefresh.GAME_TICK,
        ): NativeDrawing {
            RenderSystem.assertOnRenderThread()
            return NativeDrawing(ids.incrementAndGet(), description, refresh, drawing)
        }
    }
}

/**
 * Local native GUI coordinates. Width/height round up to cover the target; its pixel edges clip overflow. Each GUI unit
 * occupies [guiScale] pixels. Native drawing never accesses Compose state.
 */
class NativeDrawingContext
internal constructor(
    val graphics: GuiGraphicsExtractor,
    val width: Int,
    val height: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val guiScale: Float,
)

/** Limits inactive cached drawings and preparations, while every displayed drawing remains represented. */
data class NativeDrawingOptions(val cacheCapacity: Int = 0, val preparationsPerFrame: Int = 4) {
    init {
        require(cacheCapacity in 0..128 && preparationsPerFrame in 1..64)
    }
}

/** Native rectangular content rendered at its layout's physical resolution. Compose owns transforms and input. */
@Composable
fun MinecraftNativeDrawing(drawing: NativeDrawing, modifier: Modifier = Modifier) {
    val images =
        checkNotNull(LocalNativeDrawings.current) { "MinecraftNativeDrawing requires a CompixelUI screen or HUD layer" }
    NativeImageContent(drawing, drawing.description, images, modifier) { size ->
        NativeImageAtlas.Size(size.width, size.height)
    }
}

internal val LocalNativeDrawings = staticCompositionLocalOf<NativeImageMailbox<NativeDrawing>?> { null }

/** Native image invalidation policy. Only visible requested images are refreshed, within the host budget. */
class NativeRefresh private constructor(internal val kind: Kind, internal val millis: Long = 0) {
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
        @JvmField val AUTO = NativeRefresh(Kind.AUTO)
        @JvmField val STATIC = NativeRefresh(Kind.STATIC)
        /** Follows Minecraft texture ticks, including paused-world and frozen-tick behavior. */
        @JvmField val GAME_TICK = NativeRefresh(Kind.GAME_TICK)
        /** Redraws every frame, within the frame budget; only for drawings that must move faster than game ticks. */
        @JvmField val FRAME = NativeRefresh(Kind.FRAME)

        @JvmStatic
        fun every(millis: Long): NativeRefresh {
            require(millis in 16..60_000) { "Native refresh interval must be 16..60000 ms" }
            return NativeRefresh(Kind.INTERVAL, millis)
        }

        /** AUTO's result for items redrawn only when their resolved model or cooldown overlay changes. */
        internal val ON_CHANGE = NativeRefresh(Kind.ON_CHANGE)
    }
}

/** The shared scheduler's form of a resolved policy. */
internal fun NativeRefresh.scheduled(): NativeImageRefresh =
    when (kind) {
        NativeRefresh.Kind.STATIC -> NativeImageRefresh.STATIC
        NativeRefresh.Kind.GAME_TICK -> NativeImageRefresh.GAME_TICK
        NativeRefresh.Kind.FRAME -> NativeImageRefresh.FRAME
        NativeRefresh.Kind.INTERVAL -> NativeImageRefresh.every(millis)
        NativeRefresh.Kind.ON_CHANGE -> NativeImageRefresh.ON_CHANGE
        NativeRefresh.Kind.AUTO -> error("Unresolved native refresh policy")
    }
