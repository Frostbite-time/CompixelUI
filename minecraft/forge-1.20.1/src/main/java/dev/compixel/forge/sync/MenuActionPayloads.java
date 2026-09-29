package dev.compixel.forge.sync;

import dev.compixel.sync.action.ActionFailure;
import java.util.UUID;

final class MenuActionPayloads {
    record Fragment(
            int menu,
            UUID nonce,
            long request,
            UUID session,
            long sequence,
            String action,
            int total,
            int offset,
            byte[] data) {
        static final PacketCodec<Fragment> CODEC = PacketCodec.of(
                (b, p) -> {
                    b.writeVarInt(p.menu);
                    b.writeUUID(p.nonce);
                    b.writeVarLong(p.request);
                    b.writeUUID(p.session);
                    b.writeVarLong(p.sequence);
                    b.writeUtf(p.action, 64);
                    b.writeVarInt(p.total);
                    b.writeVarInt(p.offset);
                    b.writeByteArray(p.data);
                },
                b -> {
                    var fragment = new Fragment(
                            b.readVarInt(),
                            b.readUUID(),
                            b.readVarLong(),
                            b.readUUID(),
                            b.readVarLong(),
                            b.readUtf(64),
                            b.readVarInt(),
                            b.readVarInt(),
                            b.readByteArray(MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES));
                    if (fragment.total < 1
                            || fragment.offset < 0
                            || fragment.offset >= fragment.total
                            || fragment.data.length < 1
                            || fragment.data.length > fragment.total - fragment.offset)
                        throw new IllegalArgumentException("Invalid menu action fragment");
                    return fragment;
                });
    }

    record Request(int menu, UUID nonce, long request, UUID session, long sequence, String action, byte[] data) {
        static final PacketCodec<Request> CODEC = PacketCodec.of(
                (b, p) -> {
                    b.writeVarInt(p.menu);
                    b.writeUUID(p.nonce);
                    b.writeVarLong(p.request);
                    b.writeUUID(p.session);
                    b.writeVarLong(p.sequence);
                    b.writeUtf(p.action, 64);
                    b.writeByteArray(p.data);
                },
                b -> new Request(
                        b.readVarInt(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUtf(64),
                        b.readByteArray(MenuTransportLimits.MAX_ACTION_FRAGMENT_BYTES)));
    }

    record Result(
            int menu,
            UUID nonce,
            long request,
            UUID session,
            long sequence,
            MenuSync.ActionStatus status,
            ActionFailure failure,
            long actual,
            long limit,
            String detail) {
        static final PacketCodec<Result> CODEC = PacketCodec.of(
                (b, p) -> {
                    b.writeVarInt(p.menu);
                    b.writeUUID(p.nonce);
                    b.writeVarLong(p.request);
                    b.writeUUID(p.session);
                    b.writeVarLong(p.sequence);
                    b.writeEnum(p.status);
                    b.writeEnum(p.failure);
                    b.writeLong(p.actual);
                    b.writeLong(p.limit);
                    b.writeUtf(p.detail, 256);
                },
                b -> new Result(
                        b.readVarInt(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readUUID(),
                        b.readVarLong(),
                        b.readEnum(MenuSync.ActionStatus.class),
                        b.readEnum(ActionFailure.class),
                        b.readLong(),
                        b.readLong(),
                        b.readUtf(256)));
    }
}
