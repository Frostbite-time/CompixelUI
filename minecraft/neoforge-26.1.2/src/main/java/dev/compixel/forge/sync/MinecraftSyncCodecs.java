package dev.compixel.forge.sync;

import dev.compixel.sync.state.SyncCodec;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Bridges registry-aware native codecs into portable schema framing. A codec may be a shared constant: each use takes
 * the registries of the side that is synchronizing the menu, so it works only inside menu values and actions.
 */
public final class MinecraftSyncCodecs {
    public static <T> SyncCodec<T> registry(
            String id, int maximumBytes, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        if (maximumBytes < 1) throw new IllegalArgumentException("Invalid native codec limit");
        Objects.requireNonNull(codec);
        return SyncCodec.of(
                "registry:" + id + ":" + maximumBytes,
                (out, value) -> {
                    var registries = SyncRegistries.current();
                    var bytes = Unpooled.buffer(Math.min(256, maximumBytes), maximumBytes);
                    try {
                        var buffer = new RegistryFriendlyByteBuf(bytes, registries, ConnectionType.NEOFORGE);
                        codec.encode(buffer, value);
                        out.writeInt(bytes.readableBytes());
                        byte[] scratch = new byte[Math.min(4096, Math.max(1, bytes.readableBytes()))];
                        while (bytes.isReadable()) {
                            int count = Math.min(scratch.length, bytes.readableBytes());
                            bytes.readBytes(scratch, 0, count);
                            out.write(scratch, 0, count);
                        }
                    } catch (IndexOutOfBoundsException | io.netty.handler.codec.EncoderException invalid) {
                        throw new IOException(
                                "Native value cannot be encoded within its codec limit: maximumBytes=" + maximumBytes
                                        + "",
                                invalid);
                    } finally {
                        bytes.release();
                    }
                },
                in -> {
                    var registries = SyncRegistries.current();
                    int size = in.readInt();
                    if (size < 0 || size > maximumBytes) throw new IOException("Invalid native value size");
                    var bytes = Unpooled.buffer(Math.min(4096, size), Math.max(1, size));
                    try {
                        // Grow only after bytes actually arrive; a truncated length prefix must not allocate the
                        // declared value.
                        byte[] scratch = new byte[Math.min(4096, size)];
                        for (int remaining = size; remaining > 0; ) {
                            int count = Math.min(scratch.length, remaining);
                            in.readFully(scratch, 0, count);
                            bytes.writeBytes(scratch, 0, count);
                            remaining -= count;
                        }
                        T decoded =
                                codec.decode(new RegistryFriendlyByteBuf(bytes, registries, ConnectionType.NEOFORGE));
                        if (bytes.isReadable()) throw new IOException("Trailing native value data");
                        return decoded;
                    } catch (IndexOutOfBoundsException invalid) {
                        throw new IOException("Truncated native value", invalid);
                    } finally {
                        bytes.release();
                    }
                });
    }

    private MinecraftSyncCodecs() {}
}
