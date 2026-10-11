package dev.compixel.forge.sync;

import dev.compixel.sync.state.SyncCodec;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Bridges native FriendlyByteBuf codecs into portable schema framing. The buffers carry no registries, so a stateless
 * codec may be a shared constant that the server and the client both use.
 */
public final class MinecraftSyncCodecs {
    public static <T> SyncCodec<T> buffer(String id, PacketCodec<T> codec) {
        Objects.requireNonNull(codec);
        return SyncCodec.of(
                "buffer:" + id,
                (out, value) -> {
                    var bytes = Unpooled.buffer(256);
                    try {
                        var buffer = new FriendlyByteBuf(bytes);
                        codec.encode(buffer, value);
                        out.writeInt(bytes.readableBytes());
                        byte[] scratch = new byte[Math.min(4096, Math.max(1, bytes.readableBytes()))];
                        while (bytes.isReadable()) {
                            int count = Math.min(scratch.length, bytes.readableBytes());
                            bytes.readBytes(scratch, 0, count);
                            out.write(scratch, 0, count);
                        }
                    } catch (IndexOutOfBoundsException | io.netty.handler.codec.EncoderException invalid) {
                        throw new IOException("Native value cannot be encoded", invalid);
                    } finally {
                        bytes.release();
                    }
                },
                in -> {
                    int size = in.readInt();
                    if (size < 0) throw new IOException("Invalid native value size");
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
                        T decoded = codec.decode(new FriendlyByteBuf(bytes));
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
