package dev.composemc.platform;

/** Checks the client runtime before entering Kotlin code; safe to load on a server. */
public final class KotlinRuntime {
    private KotlinRuntime() {}

    public static void requireAvailable() {
        try {
            var loader = KotlinRuntime.class.getClassLoader();
            var version = Class.forName("kotlin.KotlinVersion", true, loader);
            var current = version.getField("CURRENT").get(null);
            if (!(boolean) version.getMethod("isAtLeast", int.class, int.class, int.class)
                    .invoke(current, 2, 2, 21)) {
                throw new IllegalStateException("Kotlin stdlib 2.2.21 or newer is required; found " + current);
            }
            Class.forName("kotlin.jvm.internal.Intrinsics", false, loader);
            Class.forName("kotlinx.coroutines.CoroutineDispatcher", false, loader)
                    .getMethod("limitedParallelism", int.class, String.class);
            Class.forName("kotlinx.serialization.KSerializer", false, loader).getMethod("getDescriptor");
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) {
            throw new IllegalStateException("Compose MC requires compatible Kotlin stdlib, Coroutines and "
                    + "Serialization libraries. Install Kotlin for Forge for this Minecraft version, or replace "
                    + "the standard Compose MC JAR with its with-kotlin variant. Install only one variant.", failure);
        }
    }
}
