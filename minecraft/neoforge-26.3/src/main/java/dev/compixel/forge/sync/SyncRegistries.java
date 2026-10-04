package dev.compixel.forge.sync;

import dev.compixel.sync.session.CodecScope;
import java.util.Objects;
import net.minecraft.core.RegistryAccess;

/**
 * The registries of the side that is encoding or decoding a menu's values. The sessions enter a scope around each
 * operation on the calling game thread, so one registry codec serves the integrated server and the client alike.
 */
final class SyncRegistries {
    private static final ThreadLocal<RegistryAccess> CURRENT = new ThreadLocal<>();

    static CodecScope scope(RegistryAccess registries) {
        Objects.requireNonNull(registries);
        return () -> {
            var previous = CURRENT.get();
            CURRENT.set(registries);
            return () -> {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            };
        };
    }

    static RegistryAccess current() {
        var registries = CURRENT.get();
        if (registries == null)
            throw new IllegalStateException("Registry codecs work only while CompixelUI synchronizes a menu;"
                    + " encode native values elsewhere with their StreamCodec");
        return registries;
    }

    private SyncRegistries() {}
}
