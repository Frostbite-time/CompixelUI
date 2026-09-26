package dev.composemc.forge.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.concurrent.Executors;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class MinecraftSyncCodecsTest {
    @Test
    void oneSharedBufferCodecServesBothSides() throws Exception {
        var codec = MinecraftSyncCodecs.buffer(
                "test:number", 16, PacketCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt));
        var bytes = new ByteArrayOutputStream();
        codec.write(new DataOutputStream(bytes), 42);
        // As with the integrated server and the client, another thread decodes with the same codec.
        var executor = Executors.newSingleThreadExecutor();
        try {
            int decoded = executor.submit(
                            () -> codec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))))
                    .get();
            assertEquals(42, decoded);
        } finally {
            executor.shutdown();
        }
    }
}
