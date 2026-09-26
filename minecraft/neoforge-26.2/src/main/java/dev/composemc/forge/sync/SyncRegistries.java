package dev.composemc.forge.sync;

import java.util.Objects;
import net.minecraft.core.RegistryAccess;

/**
 * The registries of the side that is encoding or decoding a menu's values. MenuSync supplies them around each
 * operation on the calling game thread, so one registry codec serves the integrated server and the client alike.
 */
final class SyncRegistries {
    private static final ThreadLocal<RegistryAccess> CURRENT = new ThreadLocal<>();

    @FunctionalInterface
    interface Work<T, E extends Exception> {
        T run() throws E;
    }

    @FunctionalInterface
    interface Step<E extends Exception> {
        void run() throws E;
    }

    static <T, E extends Exception> T with(RegistryAccess registries, Work<T, E> work) throws E {
        var previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(registries));
        try {
            return work.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static <E extends Exception> void run(RegistryAccess registries, Step<E> step) throws E {
        with(registries, () -> {
            step.run();
            return null;
        });
    }

    static RegistryAccess current() {
        var registries = CURRENT.get();
        if (registries == null)
            throw new IllegalStateException("Registry codecs work only while Compose MC synchronizes a menu;"
                    + " encode native values elsewhere with their StreamCodec");
        return registries;
    }

    private SyncRegistries() {}
}
