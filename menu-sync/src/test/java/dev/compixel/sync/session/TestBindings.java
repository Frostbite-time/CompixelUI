package dev.compixel.sync.session;

import dev.compixel.sync.MenuSyncOptions;
import dev.compixel.sync.state.SyncCodec;
import dev.compixel.sync.state.SyncSchema;

/** Minimal host subclasses: a binding with a fixed menu id, and an action with any player type. */
final class TestBindings {
    static final class Binding<M, P> extends SyncBinding<M, P, Binding<M, P>> {
        private final int id;

        Binding(M menu, SyncSchema<M> schema, MenuSyncOptions options, int id) {
            super(menu, schema, options, SyncLog.NONE);
            this.id = id;
        }

        Binding(M menu, SyncSchema<M> schema, MenuSyncOptions options) {
            this(menu, schema, options, 0);
        }

        Binding(M menu, SyncSchema<M> schema) {
            this(menu, schema, MenuSyncOptions.DEFAULT);
        }

        @Override
        protected int menuId() {
            return id;
        }
    }

    static final class Action<M, P, V> extends SyncAction<M, P, V> {
        Action(String id, SyncCodec<V> codec, int maximumBytes, Handler<M, P, V> handler) {
            super(id, codec, maximumBytes, handler);
        }

        Action(String id, SyncCodec<V> codec, Handler<M, P, V> handler) {
            this(id, codec, DEFAULT_MAX_BYTES, handler);
        }
    }

    private TestBindings() {}
}
