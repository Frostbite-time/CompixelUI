package dev.composemc.forge.sync;

import dev.composemc.sync.state.SyncCodec;
import dev.composemc.sync.SizeLimitException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.io.*;
import java.util.Objects;

/** A small typed client intent. The handler validates and changes authoritative state on the server thread. */
public final class MenuAction<M extends AbstractContainerMenu, V> {
    public static final int DEFAULT_MAX_BYTES = 8192;
    @FunctionalInterface public interface Handler<M, V> { boolean apply(M menu, ServerPlayer player, V value); }
    private final String id;
    private final SyncCodec<V> codec;
    private final Handler<M, V> handler;
    private final int maximumBytes;
    private MenuAction(String id, SyncCodec<V> codec, int maximumBytes, Handler<M, V> handler) {
        if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid action ID");
        this.id = id; this.codec = Objects.requireNonNull(codec); this.handler = Objects.requireNonNull(handler);
        if(maximumBytes<1)throw new IllegalArgumentException("Invalid action byte limit");
        this.maximumBytes=maximumBytes;
    }
    public static <M extends AbstractContainerMenu, V> MenuAction<M, V> of(String id, SyncCodec<V> codec, Handler<M, V> handler) {
        return new MenuAction<>(id, codec, DEFAULT_MAX_BYTES, handler);
    }
    /** Explicitly permit larger intents. Transport fragments them below Minecraft payload limits. */
    public static <M extends AbstractContainerMenu,V> MenuAction<M,V> of(String id,SyncCodec<V> codec,int maximumBytes,Handler<M,V> handler){
        return new MenuAction<>(id,codec,maximumBytes,handler);
    }
    public int maximumBytes(){return maximumBytes;}
    public String id() { return id; }
    String codecId() { return codec.id(); }
    byte[] encode(V value) throws IOException { return encode(value, maximumBytes); }
    byte[] encode(V value, int availableQueueBytes) throws IOException {
        int encodingLimit = Math.min(maximumBytes, availableQueueBytes);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new FilterOutputStream(bytes) {
            private int count;
            private void check(int n) throws IOException { if (n > encodingLimit - count) throw new SizeLimitException((long) count + n, encodingLimit); count += n; }
            public void write(int b) throws IOException { check(1); out.write(b); }
            public void write(byte[] b, int off, int len) throws IOException { check(len); out.write(b, off, len); }
        })) { codec.write(output, Objects.requireNonNull(value)); }
        return bytes.toByteArray();
    }
    boolean apply(M menu, ServerPlayer player, byte[] payload) throws IOException {
        if(payload.length>maximumBytes)throw new IOException("Menu action exceeds its byte limit");
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            V value;
            try{value=codec.read(input);}catch(RuntimeException invalid){throw new IOException("Invalid menu action data",invalid);}
            if (input.available() != 0) throw new IOException("Trailing menu action data");
            return handler.apply(menu, player, value);
        }
    }
}
