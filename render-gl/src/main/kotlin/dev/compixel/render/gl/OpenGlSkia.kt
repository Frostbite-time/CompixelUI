package dev.compixel.render.gl

import dev.compixel.render.*
import java.util.IdentityHashMap
import org.jetbrains.skia.DirectContext
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*

/**
 * Skia in one OpenGL context, shared by every [OpenGlFrameRenderer] that draws there: screens, HUD layers and warm-up
 * use one Skia context, so its shader programs, glyph atlases and resource cache, with one budget, serve them all, and
 * a layer that opens later finds them ready. A renderer keeps its own output target and its own images. When it closes,
 * the images that recorded pictures still reference wait here until nothing references them, and are released by the
 * next renderer that draws or by [collect].
 *
 * It lives until [close], which the host calls when the GL context ends, after its last renderer. Use it on the thread
 * that owns the GL context; [identity] names that context.
 */
class OpenGlSkia(private val identity: Any = GL.getCapabilities()) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val capabilities = GL.getCapabilities()
    internal val scopes = GlScopeStates(capabilities)
    private var context: DirectContext? = null
    private var privateVao = 0
    private val retired = RetiredImages()
    private var closed = false

    /** Renderers that are open in this context. */
    var renderers = 0
        private set

    /** Whether Skia's context exists yet; the first renderer that draws or imports an image creates it. */
    val created
        get() = context != null

    /** Images of closed renderers that recorded pictures still reference. */
    val retiredImages
        get() = retired.size

    init {
        check(capabilities.OpenGL32) { "OpenGL 3.2 is required by this backend" }
    }

    internal fun checkContext(current: Any) {
        check(Thread.currentThread() === owner) { "Renderer accessed outside its owning thread" }
        check(!closed) { "The GL context's Skia context is closed" }
        check(current === identity) { "GL context changed; recreate the renderer through the version adapter" }
    }

    /** Runs [action] between saving and restoring [state], with no foreign vertex array bound. */
    internal fun <T> isolated(
        state: GlStateSet,
        stateCheck: GlStateCheck?,
        profiler: UiFrameProfiler?,
        action: () -> T,
    ): T {
        val check = stateCheck?.before()
        val saved = profiler.measureDetail(CpuDetail.GL_CAPTURE) { state.capture() }
        try {
            if (privateVao == 0) privateVao = glGenVertexArrays()
            // Never let foreign vertex attribute setup mutate Minecraft's currently bound VAO.
            glBindVertexArray(privateVao)
            return action()
        } finally {
            profiler.measureDetail(CpuDetail.GL_RESTORE) { state.restore(saved) }
            check?.invoke()
        }
    }

    /**
     * Creating Skia's context may write anything Skia can; call inside the [GlScopeStates.skia] scope the first time.
     */
    internal fun direct(): DirectContext {
        context?.let {
            return it
        }
        // Skiko refuses OpenGL on macOS unless this is set, but this backend draws into the game's own context.
        System.setProperty("skiko.macos.opengl.enabled", "true")
        return DirectContext.makeGL().also {
            it.resourceCacheLimit = CACHE_BYTES
            context = it
            live.incrementAndGet()
        }
    }

    internal fun attach() {
        check(!closed) { "The GL context's Skia context is closed" }
        renderers++
    }

    /** A renderer closed: what it could not release yet waits here. Returns how many images that is. */
    internal fun detach(images: RetiredImages): Int {
        renderers--
        val waiting = images.moveTo(retired)
        retired.release()
        return waiting
    }

    internal fun releaseRetired() = retired.release()

    /**
     * Releases the images of closed renderers that nothing references any more. Renderers do this as they draw; a host
     * calls it now and then while none is open, for example each tick. It saves and restores the GL state it touches.
     */
    fun collect() {
        check(Thread.currentThread() === owner) { "Renderer accessed outside its owning thread" }
        if (closed || retired.size == 0) return
        isolated(scopes.releaseImages, null, null) { retired.release() }
    }

    /**
     * Closes Skia's context and what it cached, once every renderer has closed. Returns how many images recorded
     * pictures still referenced: their final release then happens elsewhere and can lose their textures, so close the
     * UI before this.
     */
    fun shutDown(): Int {
        check(Thread.currentThread() === owner) { "Renderer accessed outside its owning thread" }
        if (closed) return 0
        check(renderers == 0) { "$renderers renderers still draw with this Skia context" }
        val stranded =
            isolated(scopes.skia, null, null) {
                val stranded = retired.releaseAll()
                context?.let {
                    it.close()
                    live.decrementAndGet()
                }
                context = null
                stranded
            }
        if (privateVao != 0) glDeleteVertexArrays(privateVao)
        privateVao = 0
        closed = true
        synchronized(shared) { shared.remove(identity, this) }
        return stranded
    }

    override fun close() {
        shutDown()
    }

    companion object {
        /** One budget for every layer's glyphs, paths and scratch textures. */
        private const val CACHE_BYTES = 64L * 1024 * 1024
        private val shared = IdentityHashMap<Any, OpenGlSkia>()
        private val live = java.util.concurrent.atomic.AtomicInteger()

        /** Skia contexts that exist now, over every GL context: one per GL context that has drawn UI. */
        val liveContexts
            get() = live.get()

        /** The Skia context every renderer of the current GL context shares, created on first use. */
        fun shared(identity: Any = GL.getCapabilities()): OpenGlSkia =
            synchronized(shared) { shared.getOrPut(identity) { OpenGlSkia(identity) } }

        /** The current GL context's shared Skia context when one exists. */
        fun sharedOrNull(identity: Any = GL.getCapabilities()): OpenGlSkia? = synchronized(shared) { shared[identity] }

        /** How many GL contexts have a shared Skia context now. */
        val sharedCount
            get() = synchronized(shared) { shared.size }
    }
}
