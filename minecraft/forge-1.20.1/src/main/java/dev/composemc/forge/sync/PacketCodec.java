package dev.composemc.forge.sync;

import net.minecraft.network.FriendlyByteBuf;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Native 1.20.1 buffer codec used by the Forge channel and bounded schema bridge. */
public record PacketCodec<T>(BiConsumer<FriendlyByteBuf, T> encoder, Function<FriendlyByteBuf, T> decoder) {
    public static <T> PacketCodec<T> of(BiConsumer<FriendlyByteBuf, T> encoder, Function<FriendlyByteBuf, T> decoder) {
        return new PacketCodec<>(encoder, decoder);
    }
    public void encode(FriendlyByteBuf buffer, T value) { encoder.accept(buffer, value); }
    public T decode(FriendlyByteBuf buffer) { return decoder.apply(buffer); }
}
