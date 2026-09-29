package dev.compixel.forge.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class MinecraftSyncCodecsTest {
    /** Records the registries that each encode and decode received. */
    private static StreamCodec<RegistryFriendlyByteBuf, Integer> recording(List<RegistryAccess> seen) {
        return StreamCodec.of(
                (buffer, value) -> {
                    seen.add(buffer.registryAccess());
                    buffer.writeVarInt(value);
                },
                buffer -> {
                    seen.add(buffer.registryAccess());
                    return buffer.readVarInt();
                });
    }

    /** Registries with an identity only; the recording codec never looks inside them. */
    private static RegistryAccess registries() {
        return (RegistryAccess) Proxy.newProxyInstance(
                RegistryAccess.class.getClassLoader(),
                new Class<?>[] {RegistryAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void oneSharedCodecUsesTheRegistriesOfEachSide() throws Exception {
        List<RegistryAccess> seen = Collections.synchronizedList(new ArrayList<>());
        var codec = MinecraftSyncCodecs.registry("test:number", 16, recording(seen));
        var server = registries();
        var client = registries();
        var bytes = new ByteArrayOutputStream();
        SyncRegistries.run(server, () -> codec.write(new DataOutputStream(bytes), 42));
        // As with the integrated server and the client, another thread decodes with its own registries.
        var executor = Executors.newSingleThreadExecutor();
        try {
            int decoded = executor.submit(() -> SyncRegistries.with(
                            client,
                            () -> codec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))))
                    .get();
            assertEquals(42, decoded);
        } finally {
            executor.shutdown();
        }
        assertEquals(List.of(server, client), seen);
    }

    @Test
    void registryCodecsWorkOnlyWhileMenusSynchronize() {
        var codec = MinecraftSyncCodecs.registry("test:number", 16, recording(new ArrayList<>()));
        var outside = assertThrows(
                IllegalStateException.class, () -> codec.write(new DataOutputStream(new ByteArrayOutputStream()), 1));
        assertTrue(outside.getMessage().contains("synchronizes a menu"));
        // A finished operation leaves no registries behind for later work on the same thread.
        SyncRegistries.run(registries(), () -> {});
        assertThrows(IllegalStateException.class, SyncRegistries::current);
    }
}
