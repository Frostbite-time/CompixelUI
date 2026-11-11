package dev.compixel.bridge;

import androidx.compose.ui.platform.PlatformRootForTest;
import androidx.compose.ui.spatial.ExecuteDelayed;
import androidx.compose.ui.spatial.RectManager;
import java.lang.reflect.Field;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * Delayed execution for a Compose root on its scene's thread. Compose 1.12 gives every root a RectManager whose delayed
 * callbacks run on Skiko's main dispatcher, the AWT event thread, where they would touch the root's layout data beside
 * the scene's own thread. Compose has the seam for another thread, RectManager's ExecuteDelayed, but offers no way to
 * set it, so {@link #install} replaces it when a root is created.
 *
 * <p>Java keeps Compose's internal declarations compiled against: a Compose version that renames them fails the build.
 * Only the field itself is looked up at run time, and a miss fails the first scene.
 */
final class RootDelays implements ExecuteDelayed {
    private static final Field EXECUTE_DELAYED;

    static {
        try {
            Field field = RectManager.class.getDeclaredField("executeDelayed");
            if (field.getType() != ExecuteDelayed.class)
                throw new NoSuchFieldException("RectManager.executeDelayed is a " + field.getType());
            field.setAccessible(true);
            EXECUTE_DELAYED = field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "This Compose version keeps its roots' delayed callbacks elsewhere; CompixelUI needs Compose 1.12.0",
                    error);
        }
    }

    private final BiFunction<Long, Runnable, Object> schedule;
    private final Consumer<Object> cancel;

    /** {@code schedule} runs a block after a delay in milliseconds and returns the token {@code cancel} takes. */
    RootDelays(BiFunction<Long, Runnable, Object> schedule, Consumer<Object> cancel) {
        this.schedule = schedule;
        this.cancel = cancel;
    }

    /** Gives {@code root}, which was just created, these delays in place of the AWT event thread's. */
    void install(PlatformRootForTest root) {
        RectManager manager = root.getSemanticsOwner()
                .getUnmergedRootSemanticsNode()
                .getLayoutNode$ui()
                .getOwner$ui()
                .getRectManager();
        // Nothing is scheduled this early; if a later Compose does, it must not stay behind on the other thread.
        manager.removeScheduledCallback();
        try {
            EXECUTE_DELAYED.set(manager, this);
            if (EXECUTE_DELAYED.get(manager) != this) throw new IllegalStateException("The field kept its value");
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "Cannot move a Compose root's delayed callbacks to its scene's thread", error);
        }
    }

    @Override
    public Object executeDelayed(long delayMillis, Function0<Unit> block) {
        return schedule.apply(delayMillis, block::invoke);
    }

    @Override
    public void removeDelayedExecution(Object token) {
        cancel.accept(token);
    }
}
