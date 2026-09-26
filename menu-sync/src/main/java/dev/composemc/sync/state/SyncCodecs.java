package dev.composemc.sync.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class SyncCodecs {
    public static final SyncCodec<Integer> INT = SyncCodec.of("int32", (out, v) -> out.writeInt(v), in -> in.readInt());
    public static final SyncCodec<Long> LONG = SyncCodec.of("int64", (out, v) -> out.writeLong(v), in -> in.readLong());
    public static final SyncCodec<java.util.UUID> UUID = SyncCodec.of(
            "uuid128",
            (out, v) -> {
                out.writeLong(v.getMostSignificantBits());
                out.writeLong(v.getLeastSignificantBits());
            },
            in -> new java.util.UUID(in.readLong(), in.readLong()));
    public static final SyncCodec<Boolean> BOOLEAN = SyncCodec.of("bool", (out, v) -> out.writeBoolean(v), in -> {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid boolean");
        return value == 1;
    });

    /** Compact enum values whose wire identity includes the declared ordering and names. */
    public static <E extends Enum<E>> SyncCodec<E> enumeration(Class<E> type) {
        E[] values = type.getEnumConstants();
        if (values == null || values.length == 0 || values.length > 256)
            throw new IllegalArgumentException("Expected 1..256 enum values");
        String identity = type.getName() + ":"
                + java.util.Arrays.stream(values).map(Enum::name).collect(java.util.stream.Collectors.joining("|"));
        final String id;
        try {
            id = "enum:"
                    + java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        return SyncCodec.of(
                id,
                (out, value) -> {
                    if (value.getDeclaringClass() != type) throw new IOException("Wrong enum type");
                    out.writeByte(value.ordinal());
                },
                in -> {
                    int ordinal = in.readUnsignedByte();
                    if (ordinal >= values.length) throw new IOException("Unknown enum value");
                    return values[ordinal];
                });
    }

    /** UTF-8 byte limit, independent of writeUTF's 64K format and Minecraft NBT quotas. Decoding allocates for received bytes, not the declared length. */
    public static SyncCodec<String> string(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid string limit");
        return SyncCodec.of(
                "utf8:" + maxBytes,
                (out, value) -> {
                    if (value.length() > maxBytes) throw new IOException("String exceeds codec limit");
                    ByteBuffer encoded = StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(value));
                    byte[] bytes = new byte[encoded.remaining()];
                    encoded.get(bytes);
                    if (bytes.length > maxBytes) throw new IOException("String exceeds codec limit");
                    out.writeInt(bytes.length);
                    out.write(bytes);
                },
                in -> {
                    int length = in.readInt();
                    if (length < 0 || length > maxBytes) throw new IOException("Invalid string length: " + length);
                    byte[] bytes = DeclaredBytes.read(in, length, "string");
                    return StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
                });
    }

    private SyncCodecs() {}
}
