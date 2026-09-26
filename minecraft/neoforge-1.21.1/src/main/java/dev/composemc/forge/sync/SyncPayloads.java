package dev.composemc.forge.sync;

import dev.composemc.sync.state.SyncBatch;
import dev.composemc.sync.state.SyncLimits;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class SyncPayloads {
    static final int ACK = 0, CLOSE = 1, READY = 2;
    static final UUID NO_SESSION = new UUID(0, 0);

    record Control(
            int kind,
            int menu,
            UUID nonce,
            long request,
            UUID session,
            String schema,
            String fingerprint,
            long revision,
            int batch)
            implements CustomPacketPayload {
        static final Type<Control> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("composemc", "menu_control"));
        static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeByte(p.kind);
                    b.writeInt(p.menu);
                    b.writeUUID(p.nonce);
                    b.writeLong(p.request);
                    b.writeUUID(p.session);
                    if (p.kind == ACK) {
                        b.writeLong(p.revision);
                        b.writeInt(p.batch);
                    }
                },
                b -> {
                    int kind = b.readUnsignedByte(), menu = b.readInt();
                    UUID nonce = b.readUUID();
                    long request = b.readLong();
                    UUID session = b.readUUID();
                    if (kind > READY || menu < 0 || request < 1)
                        throw new IllegalArgumentException("Invalid menu control");
                    return new Control(
                            kind,
                            menu,
                            nonce,
                            request,
                            session,
                            "",
                            "",
                            kind == ACK ? b.readLong() : 0,
                            kind == ACK ? b.readInt() : 0);
                });

        public Type<Control> type() {
            return TYPE;
        }
    }

    record Data(int menu, UUID nonce, long request, UUID session, SyncBatch batch, String failure)
            implements CustomPacketPayload {
        static final Type<Data> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("composemc", "menu_data"));
        static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeInt(p.menu);
                    b.writeUUID(p.nonce);
                    b.writeLong(p.request);
                    b.writeUUID(p.session);
                    b.writeBoolean(p.batch != null);
                    if (p.batch != null) {
                        try {
                            p.batch.write(new ByteBufOutputStream(b));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    } else b.writeUtf(p.failure, 256);
                },
                b -> {
                    int menu = b.readInt();
                    UUID nonce = b.readUUID();
                    long request = b.readLong();
                    UUID session = b.readUUID();
                    boolean data = b.readBoolean();
                    if (menu < 0 || request < 1) throw new IllegalArgumentException("Invalid menu data");
                    if (data) {
                        try {
                            return new Data(
                                    menu,
                                    nonce,
                                    request,
                                    session,
                                    SyncBatch.read(
                                            new ByteBufInputStream(b), MenuTransportLimits.MAX_STATE_BATCH_BYTES),
                                    "");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    return new Data(menu, nonce, request, session, null, b.readUtf(256));
                });

        public Type<Data> type() {
            return TYPE;
        }
    }
    /** First server-to-client message for an opened synchronized menu. */
    record Bootstrap(
            int menu, UUID nonce, long request, UUID session, String schema, String fingerprint, SyncLimits limits)
            implements CustomPacketPayload {
        static final Type<Bootstrap> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("composemc", "menu_bootstrap"));
        static final StreamCodec<RegistryFriendlyByteBuf, Bootstrap> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeInt(p.menu);
                    b.writeUUID(p.nonce);
                    b.writeLong(p.request);
                    b.writeUUID(p.session);
                    b.writeUtf(p.schema, 128);
                    b.writeUtf(p.fingerprint, 64);
                    try {
                        p.limits.write(new ByteBufOutputStream(b));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                b -> {
                    int menu = b.readInt();
                    UUID nonce = b.readUUID();
                    long request = b.readLong();
                    UUID session = b.readUUID();
                    if (menu < 0 || request < 1 || session.equals(NO_SESSION))
                        throw new IllegalArgumentException("Invalid menu bootstrap");
                    String schema = b.readUtf(128), fingerprint = b.readUtf(64);
                    try {
                        var limits = SyncLimits.read(new ByteBufInputStream(b));
                        if (limits.batchBytes() > MenuTransportLimits.MAX_STATE_BATCH_BYTES)
                            throw new IOException("State batch exceeds transport envelope");
                        return new Bootstrap(menu, nonce, request, session, schema, fingerprint, limits);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        public Type<Bootstrap> type() {
            return TYPE;
        }
    }

    private SyncPayloads() {}
}
