package dev.composemc.sync.state;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/** Stable wire identity plus bounded value encoding. Values must be immutable snapshots. */
public interface SyncCodec<T> {
    String id();

    void write(DataOutput output, T value) throws IOException;

    T read(DataInput input) throws IOException;

    @FunctionalInterface
    interface Writer<T> {
        void write(DataOutput out, T value) throws IOException;
    }

    @FunctionalInterface
    interface Reader<T> {
        T read(DataInput in) throws IOException;
    }

    static <T> SyncCodec<T> of(String id, Writer<T> writer, Reader<T> reader) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(writer);
        Objects.requireNonNull(reader);
        if (id.isBlank() || id.length() > 128) throw new IllegalArgumentException("Invalid codec identity");
        return new SyncCodec<>() {
            public String id() {
                return id;
            }

            public void write(DataOutput out, T value) throws IOException {
                writer.write(out, value);
            }

            public T read(DataInput in) throws IOException {
                return reader.read(in);
            }
        };
    }
}
