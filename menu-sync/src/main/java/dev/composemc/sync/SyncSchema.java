package dev.composemc.sync;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/**
 * Declarative server-to-client state. Getters return immutable values/collections. Setters perform
 * plain client-thread assignments and must not throw or cause external side effects.
 * Keyed collections synchronize membership/values, not source iteration-order changes.
 */
public final class SyncSchema<M> {
    static final int SET = 0, CLEAR = 1, PUT = 2, REMOVE = 3;
    final List<Binding<M>> bindings;
    private final String id;
    private final String fingerprint;

    private SyncSchema(String id, int version, List<Binding<M>> bindings) {
        this.id = id; this.bindings = List.copyOf(bindings);
        try {
            ByteArrayOutputStream identity = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(identity)) {
                out.writeUTF(id); out.writeInt(version); out.writeInt(bindings.size());
                for (Binding<M> b : bindings) {
                    out.writeUTF(b.name); out.writeUTF(b.origin); out.writeUTF(b.codec.id()); out.writeBoolean(b.keyCodec != null);
                    out.writeBoolean(b.mapSnapshot);
                    if (b.keyCodec != null) out.writeUTF(b.keyCodec.id());
                }
            }
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String id() { return id; }
    public String fingerprint() { return fingerprint; }
    public static <M> Builder<M> builder(String id, int version) { return new Builder<>(id, version); }

    public static final class Builder<M> {
        private final String id; private final int version;
        private final List<Binding<M>> bindings = new ArrayList<>();
        private Builder(String id, int version) {
            if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || id.length() > 128 || version < 1)
                throw new IllegalArgumentException("Invalid schema identity");
            this.id = id; this.version = version;
        }
        public <T> Builder<M> field(String name, SyncCodec<T> codec, Function<M, T> getter, BiConsumer<M, T> setter) {
            add(new Binding<>(name, codec, getter, setter, null, null)); return this;
        }
        public <K, V> Builder<M> keyedCollection(String name, SyncCodec<K> keyCodec, SyncCodec<V> valueCodec,
                Function<V, K> key, Function<M, ? extends Collection<V>> getter, BiConsumer<M, List<V>> setter) {
            add(Binding.collection(name, keyCodec, valueCodec, key, getter, setter)); return this;
        }
        /** Immutable keyed snapshots share unchanged data in capture, diff and client publication. */
        public <K, V> Builder<M> keyedMap(String name, SyncCodec<K> keyCodec, SyncCodec<V> valueCodec,
                Function<V, K> key, Function<M, SyncMap<K, V>> getter, BiConsumer<M, SyncMap<K, V>> setter) {
            add(new Binding<>(name, valueCodec, getter, setter, Objects.requireNonNull(keyCodec), Objects.requireNonNull(key), "", true));
            return this;
        }
        /** Include a reusable submodel under a field namespace. Its schema identity joins the handshake. */
        public <N> Builder<M> include(String prefix, SyncSchema<N> schema, Function<? super M, ? extends N> model) {
            Objects.requireNonNull(schema); Objects.requireNonNull(model);
            if(prefix==null||!prefix.matches("[a-zA-Z0-9_.-]{1,32}"))throw new IllegalArgumentException("Invalid schema namespace");
            // Validate the entire inclusion before changing the builder.
            var additions=new ArrayList<Binding<M>>();
            for(var source:schema.bindings) {
                String name=prefix+"."+source.name;
                if(name.length()>64||bindings.stream().anyMatch(b->b.name.equals(name)))throw new IllegalArgumentException("Duplicate or excessive field name");
                var binding=new Binding<M>(name,source.codec,m->source.getter.apply(model.apply(m)),
                        (m,v)->source.setter.accept(model.apply(m),v),source.keyCodec,source.key,schema.fingerprint(),source.mapSnapshot);
                additions.add(binding);
            }
            if(additions.size()>Integer.MAX_VALUE-bindings.size())throw new IllegalArgumentException("Schema exceeds Java collection capacity");
            bindings.addAll(additions);return this;
        }
        /** Include fields declared against the same model or one of its base types. */
        public Builder<M> include(String prefix, SyncSchema<? super M> schema) { return include(prefix,schema,m->m); }
        private void add(Binding<M> binding) {
            if (binding.name == null || !binding.name.matches("[a-zA-Z0-9_.-]{1,64}") ||
                bindings.stream().anyMatch(b -> b.name.equals(binding.name))) throw new IllegalArgumentException("Invalid/duplicate field name");
            bindings.add(binding);
        }
        public SyncSchema<M> build() { if (bindings.isEmpty()) throw new IllegalStateException("Empty sync schema"); return new SyncSchema<>(id, version, bindings); }
    }

    @SuppressWarnings("unchecked")
    static final class Binding<M> {
        final String name; final SyncCodec<Object> codec, keyCodec;
        final String origin;
        final boolean mapSnapshot;
        final Function<M, Object> getter; final BiConsumer<M, Object> setter; final Function<Object, Object> key;
        <T> Binding(String name, SyncCodec<T> codec, Function<M, T> getter, BiConsumer<M, T> setter, SyncCodec<?> keyCodec, Function<?, ?> key) {
            this(name,codec,getter,setter,keyCodec,key,"",false);
        }
        <T> Binding(String name, SyncCodec<?> codec, Function<M, T> getter, BiConsumer<M, T> setter, SyncCodec<?> keyCodec, Function<?, ?> key, String origin, boolean mapSnapshot) {
            this.origin=origin;
            this.mapSnapshot=mapSnapshot;
            this.name = name; this.codec = (SyncCodec<Object>) Objects.requireNonNull(codec);
            this.getter = (Function<M, Object>) Objects.requireNonNull(getter); this.setter = (BiConsumer<M, Object>) Objects.requireNonNull(setter);
            this.keyCodec = (SyncCodec<Object>) keyCodec; this.key = (Function<Object, Object>) key;
        }
        static <M,K,V> Binding<M> collection(String name, SyncCodec<K> kc, SyncCodec<V> vc, Function<V,K> key,
                Function<M, ? extends Collection<V>> get, BiConsumer<M,List<V>> set) {
            return new Binding(name, vc, get, set, Objects.requireNonNull(kc), Objects.requireNonNull(key));
        }
    }

    static final class State {
        final Object[] values, sources;
        State(int size) { values = new Object[size]; sources = new Object[size]; }
    }
    record Operation(int field, int kind, Object key, Object value) {}

    @SuppressWarnings("unchecked")
    State capture(M menu, State previous, SyncLimits limits) {
        State state = new State(bindings.size());
        long entries = 0;
        for (int i = 0; i < bindings.size(); i++) {
            Binding<M> b = bindings.get(i);
            Object value = Objects.requireNonNull(b.getter.apply(menu), "Null sync value: " + b.name);
            state.sources[i] = value;
            if (b.keyCodec == null) state.values[i] = value;
            else {
                Map<Object,Object> map;
                if (b.mapSnapshot) {
                    var snapshot = (SyncMap<Object,Object>)value;
                    if (snapshot.size() > limits.maxEntries()) throw new SyncException("Collection entry limit exceeded");
                    var prior = previous == null ? SyncMap.<Object,Object>empty() : (SyncMap<Object,Object>)previous.values[i];
                    snapshot.forEachChange(prior, (key, before, current) -> {
                        if (current != null && !key.equals(b.key.apply(current))) throw new SyncException("Collection key/value mismatch in " + b.name);
                    });
                    map = snapshot;
                }
                else if (previous != null && previous.sources[i] == value) map = (Map<Object,Object>) previous.values[i];
                else {
                    Collection<?> values = (Collection<?>) value;
                    if (values.size() > limits.maxEntries()) throw new SyncException("Collection entry limit exceeded");
                    map = new LinkedHashMap<>();
                    for (Object item : values) {
                        Object key = Objects.requireNonNull(b.key.apply(Objects.requireNonNull(item)));
                        if (map.putIfAbsent(key, item) != null) throw new SyncException("Duplicate key in " + b.name);
                    }
                }
                entries += map.size();
                if (entries > limits.maxEntries()) throw new SyncException("Total collection entry limit exceeded");
                state.values[i] = map;
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    List<Operation> difference(State old, State next) {
        List<Operation> ops = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).keyCodec == null) {
                if (old == null || !Objects.equals(old.values[i], next.values[i])) ops.add(new Operation(i, SET, null, next.values[i]));
            } else if (old == null) {
                ops.add(new Operation(i, CLEAR, null, null));
                for (var e : ((Map<Object,Object>) next.values[i]).entrySet()) ops.add(new Operation(i, PUT, e.getKey(), e.getValue()));
            } else if (old.values[i] != next.values[i]) {
                Map<Object,Object> before = (Map<Object,Object>) old.values[i], after = (Map<Object,Object>) next.values[i];
                if (after.isEmpty() && !before.isEmpty()) { ops.add(new Operation(i, CLEAR, null, null)); continue; }
                if (bindings.get(i).mapSnapshot) {
                    int field = i; var puts = new ArrayList<Operation>();
                    ((SyncMap<Object,Object>)after).forEachChange((SyncMap<Object,Object>)before, (key, prior, value) -> {
                        if (value == null) ops.add(new Operation(field, REMOVE, key, null));
                        else puts.add(new Operation(field, PUT, key, value));
                    });
                    // Remove before insert so replacing entries at the configured limit stays within the budget.
                    ops.addAll(puts); continue;
                }
                for (Object key : before.keySet()) if (!after.containsKey(key)) ops.add(new Operation(i, REMOVE, key, null));
                for (var e : after.entrySet()) if (!Objects.equals(before.get(e.getKey()), e.getValue())) ops.add(new Operation(i, PUT, e.getKey(), e.getValue()));
            }
        }
        return ops;
    }

    byte[] encode(Operation op, int limit) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new FilterOutputStream(bytes) {
            private int size;
            private void count(int n) throws IOException { if (n > limit - size) throw new SizeLimitException((long) size + n, limit); size += n; }
            public void write(int value) throws IOException { count(1); this.out.write(value); }
            public void write(byte[] b, int off, int len) throws IOException { count(len); this.out.write(b, off, len); }
        })) {
            out.writeInt(op.field); out.writeByte(op.kind);
            Binding<M> b = bindings.get(op.field);
            if (op.kind == PUT || op.kind == REMOVE) b.keyCodec.write(out, op.key);
            if (op.kind == SET || op.kind == PUT) b.codec.write(out, op.value);
        } catch (IOException | RuntimeException e) { throw new SyncException("Could not encode field " + bindings.get(op.field).name + ": " + e.getMessage(), e); }
        return bytes.toByteArray();
    }

    Operation decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int field = in.readInt(), kind = in.readUnsignedByte();
            if (field < 0 || field >= bindings.size() || kind > REMOVE) throw new IOException("Unknown sync operation");
            Binding<M> b = bindings.get(field);
            if ((b.keyCodec == null) != (kind == SET)) throw new IOException("Wrong operation for field");
            Object key = kind == PUT || kind == REMOVE ? Objects.requireNonNull(b.keyCodec.read(in)) : null;
            Object value = kind == SET || kind == PUT ? Objects.requireNonNull(b.codec.read(in)) : null;
            if (kind == PUT && !key.equals(b.key.apply(value))) throw new IOException("Collection key/value mismatch");
            if (in.available() != 0) throw new IOException("Trailing record bytes");
            return new Operation(field, kind, key, value);
        } catch (IOException | RuntimeException e) { throw new SyncException("Invalid sync record", e); }
    }

    @SuppressWarnings("unchecked")
    void apply(M menu, State state, boolean[] changed) {
        // All decoding and validation completes before any setter. Run on a single owner thread.
        for (int i = 0; i < bindings.size(); i++) if (changed[i]) {
            Binding<M> b = bindings.get(i);
            b.setter.accept(menu, b.keyCodec == null || b.mapSnapshot ? state.values[i] : List.copyOf(((Map<Object,Object>) state.values[i]).values()));
        }
    }
}
