package dev.compixel.forge.sync;

import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.session.SyncMessage;
import dev.compixel.sync.state.SyncBatch;
import dev.compixel.sync.state.SyncLimits;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Minecraft payloads that carry menu synchronization's messages; their codecs are this adapter's wire format. */
final class SyncPayloads {
    static CustomPacketPayload of(SyncMessage message) {
        return switch (message) {
            case SyncMessage.Control control -> new Control(control);
            case SyncMessage.Bootstrap bootstrap -> new Bootstrap(bootstrap);
            case SyncMessage.Data data -> new Data(data);
            case SyncMessage.ActionRequest request -> new ActionRequest(request);
            case SyncMessage.ActionFragment fragment -> new ActionFragment(fragment);
            case SyncMessage.ActionReply reply -> new ActionReply(reply);
        };
    }

    record Control(SyncMessage.Control message) implements CustomPacketPayload {
        static final Type<Control> TYPE = new Type<>(Identifier.fromNamespaceAndPath("compixel", "menu_control"));
        static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of(
                (b, p) -> {
                    var m = p.message;
                    b.writeByte(m.kind());
                    b.writeInt(m.menu());
                    b.writeUUID(m.nonce());
                    b.writeLong(m.request());
                    b.writeUUID(m.session());
                    if (m.kind() == SyncMessage.Control.ACK) {
                        b.writeLong(m.revision());
                        b.writeInt(m.batch());
                    }
                },
                b -> {
                    int kind = b.readUnsignedByte(), menu = b.readInt();
                    UUID nonce = b.readUUID();
                    long request = b.readLong();
                    UUID session = b.readUUID();
                    boolean ack = kind == SyncMessage.Control.ACK;
                    return new Control(new SyncMessage.Control(
                            kind, menu, nonce, request, session, ack ? b.readLong() : 0, ack ? b.readInt() : 0));
                });

        @Override
        public Type<Control> type() {
            return TYPE;
        }
    }

    record Data(SyncMessage.Data message) implements CustomPacketPayload {
        static final Type<Data> TYPE = new Type<>(Identifier.fromNamespaceAndPath("compixel", "menu_data"));
        static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of(
                (b, p) -> {
                    var m = p.message;
                    b.writeInt(m.menu());
                    b.writeUUID(m.nonce());
                    b.writeLong(m.request());
                    b.writeUUID(m.session());
                    b.writeBoolean(m.batch() != null);
                    if (m.batch() != null) {
                        try {
                            m.batch().write(new ByteBufOutputStream(b));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    } else b.writeUtf(m.failure(), 256);
                },
                b -> {
                    int menu = b.readInt();
                    UUID nonce = b.readUUID();
                    long request = b.readLong();
                    UUID session = b.readUUID();
                    try {
                        SyncBatch batch = b.readBoolean()
                                ? SyncBatch.read(new ByteBufInputStream(b), MenuTransportLimits.MAX_STATE_BATCH_BYTES)
                                : null;
                        String failure = batch == null ? b.readUtf(256) : "";
                        return new Data(new SyncMessage.Data(menu, nonce, request, session, batch, failure));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        @Override
        public Type<Data> type() {
            return TYPE;
        }
    }

    record Bootstrap(SyncMessage.Bootstrap message) implements CustomPacketPayload {
        static final Type<Bootstrap> TYPE = new Type<>(Identifier.fromNamespaceAndPath("compixel", "menu_bootstrap"));
        static final StreamCodec<RegistryFriendlyByteBuf, Bootstrap> CODEC = StreamCodec.of(
                (b, p) -> {
                    var m = p.message;
                    b.writeInt(m.menu());
                    b.writeUUID(m.nonce());
                    b.writeLong(m.request());
                    b.writeUUID(m.session());
                    b.writeUtf(m.schema(), 128);
                    b.writeUtf(m.fingerprint(), 64);
                    try {
                        m.limits().write(new ByteBufOutputStream(b));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                b -> {
                    int menu = b.readInt();
                    UUID nonce = b.readUUID();
                    long request = b.readLong();
                    UUID session = b.readUUID();
                    String schema = b.readUtf(128), fingerprint = b.readUtf(64);
                    try {
                        var limits = SyncLimits.read(new ByteBufInputStream(b));
                        if (limits.batchBytes() > MenuTransportLimits.MAX_STATE_BATCH_BYTES)
                            throw new IOException("State batch exceeds transport envelope");
                        return new Bootstrap(
                                new SyncMessage.Bootstrap(menu, nonce, request, session, schema, fingerprint, limits));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        @Override
        public Type<Bootstrap> type() {
            return TYPE;
        }
    }

    record ActionRequest(SyncMessage.ActionRequest message) implements CustomPacketPayload {
        static final Type<ActionRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("compixel", "menu_action"));
        static final StreamCodec<RegistryFriendlyByteBuf, ActionRequest> CODEC = StreamCodec.of(
                (b, p) -> {
                    var m = p.message;
                    b.writeVarInt(m.menu());
                    b.writeUUID(m.nonce());
                    b.writeVarLong(m.request());
                    b.writeUUID(m.session());
                    b.writeVarLong(m.sequence());
                    b.writeUtf(m.action(), 64);
                    b.writeByteArray(m.data());
                },
                b -> new ActionRequest(new SyncMessage.ActionRequest(
                        b.readVarInt(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUtf(64),
                        b.readByteArray(MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES))));

        @Override
        public Type<ActionRequest> type() {
            return TYPE;
        }
    }

    record ActionFragment(SyncMessage.ActionFragment message) implements CustomPacketPayload {
        static final Type<ActionFragment> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("compixel", "menu_action_fragment"));
        static final StreamCodec<RegistryFriendlyByteBuf, ActionFragment> CODEC = StreamCodec.of(
                (b, p) -> {
                    var m = p.message;
                    b.writeVarInt(m.menu());
                    b.writeUUID(m.nonce());
                    b.writeVarLong(m.request());
                    b.writeUUID(m.session());
                    b.writeVarLong(m.sequence());
                    b.writeUtf(m.action(), 64);
                    b.writeVarInt(m.total());
                    b.writeVarInt(m.offset());
                    b.writeByteArray(m.data());
                },
                b -> new ActionFragment(new SyncMessage.ActionFragment(
                        b.readVarInt(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUtf(64),
                        b.readVarInt(),
                        b.readVarInt(),
                        b.readByteArray(MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES))));

        @Override
        public Type<ActionFragment> type() {
            return TYPE;
        }
    }

    record ActionReply(SyncMessage.ActionReply message) implements CustomPacketPayload {
        static final Type<ActionReply> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("compixel", "menu_action_result"));
        static final StreamCodec<RegistryFriendlyByteBuf, ActionReply> CODEC = StreamCodec.of(
                (b, p) -> {
                    var m = p.message;
                    b.writeVarInt(m.menu());
                    b.writeUUID(m.nonce());
                    b.writeVarLong(m.request());
                    b.writeUUID(m.session());
                    b.writeVarLong(m.sequence());
                    b.writeEnum(m.status());
                    b.writeEnum(m.failure());
                    b.writeLong(m.actual());
                    b.writeLong(m.limit());
                    b.writeUtf(m.detail(), 256);
                },
                b -> new ActionReply(new SyncMessage.ActionReply(
                        b.readVarInt(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readEnum(ActionStatus.class),
                        b.readEnum(ActionFailure.class),
                        b.readLong(),
                        b.readLong(),
                        b.readUtf(256))));

        @Override
        public Type<ActionReply> type() {
            return TYPE;
        }
    }

    private SyncPayloads() {}
}
