package dev.composemc.forge.sync;

import dev.composemc.sync.action.ActionFailure;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

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
            byte[] data)
            implements CustomPacketPayload {
        static final Type<Fragment> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("composemc", "menu_action_fragment"));
        static final StreamCodec<RegistryFriendlyByteBuf, Fragment> CODEC = StreamCodec.of(
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

        @Override
        public Type<Fragment> type() {
            return TYPE;
        }
    }

    record Request(int menu, UUID nonce, long request, UUID session, long sequence, String action, byte[] data)
            implements CustomPacketPayload {
        static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("composemc", "menu_action"));
        static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
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

        @Override
        public Type<Request> type() {
            return TYPE;
        }
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
            String detail)
            implements CustomPacketPayload {
        static final Type<Result> TYPE = new Type<>(Identifier.fromNamespaceAndPath("composemc", "menu_action_result"));
        static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.of(
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

        @Override
        public Type<Result> type() {
            return TYPE;
        }
    }
}
