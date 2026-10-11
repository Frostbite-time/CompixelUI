package dev.compixel.sync.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.state.SyncBatch;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncPublisher;
import dev.compixel.sync.state.SyncSchema;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SyncBindingTest {
    private static final SyncSchema<Object> SCHEMA = SyncSchema.builder("test:binding", 1)
            .field("v", SyncCodecs.INT, m -> 1, (m, v) -> {})
            .build();

    private static SyncSchema<Object> text(AtomicReference<String> text) {
        return SyncSchema.builder("test:readiness", 1)
                .field("text", SyncCodecs.STRING, m -> text.get(), (m, v) -> text.set(v))
                .build();
    }

    @Test
    void declaredActionsJoinTheFingerprintAndCloseWithTheSession() {
        try (var binding = new TestBindings.Binding<Object, Object>(null, SCHEMA)) {
            String empty = binding.fingerprint();
            for (int i = 0; i < 65; i++)
                binding.action(new TestBindings.Action<>("action_" + i, SyncCodecs.INT, (m, p, v) -> true));
            assertNotEquals(empty, binding.fingerprint());
            assertThrows(
                    IllegalStateException.class,
                    () -> binding.action(new TestBindings.Action<>("action_0", SyncCodecs.INT, (m, p, v) -> true)));
            binding.clientStart();
            assertThrows(
                    IllegalStateException.class,
                    () -> binding.action(new TestBindings.Action<>("late", SyncCodecs.INT, (m, p, v) -> true)));
            assertThrows(IllegalStateException.class, () -> binding.onUpdate(m -> {}));
        }
    }

    @Test
    void committedStateStaysInteractiveWhileLaterBatchesAreIncomplete() {
        var server = new AtomicReference<>("initial");
        var client = new AtomicReference<>("");
        var publications = new AtomicInteger();
        try (var publisher = new SyncPublisher<>(text(server));
                var binding = new TestBindings.Binding<>(null, text(client))) {
            binding.onUpdate(m -> publications.incrementAndGet());
            binding.clientStart();
            assertFalse(binding.hasSnapshot());
            publisher.pump(null, 0, batch -> {
                binding.receive(CodecScope.NONE, batch);
                publisher.acknowledge(batch.revision(), batch.index());
            });
            assertTrue(binding.hasSnapshot());
            assertEquals("initial", client.get());
            assertEquals(1, publications.get());
            // Larger than a window, so the update needs several ticks while acknowledgements come back.
            server.set("x".repeat(3 * SyncPublisher.WINDOW_BYTES / 2));
            var sent = new ArrayList<SyncBatch>();
            publisher.pump(null, 1, sent::add);
            for (var batch : sent) binding.receive(CodecScope.NONE, batch);
            assertEquals(SyncStatus.SYNCING, binding.status());
            assertTrue(binding.hasSnapshot());
            assertEquals("initial", client.get());
            assertEquals(1, publications.get());
            for (int tick = 2; tick < 20 && publisher.busy(); tick++) {
                for (var batch : sent) publisher.acknowledge(batch.revision(), batch.index());
                sent.clear();
                publisher.pump(null, tick, sent::add);
                for (var batch : sent) binding.receive(CodecScope.NONE, batch);
            }
            assertEquals(server.get(), client.get());
            assertTrue(binding.hasSnapshot());
            assertEquals(2, publications.get());
            binding.fail("disconnected");
            assertFalse(binding.hasSnapshot());
            assertEquals("disconnected", binding.statistics().failure());
            binding.clientStart();
            assertFalse(binding.hasSnapshot());
        }
    }

    @Test
    void serverCanSendTheInitialSnapshotInTheSameTickItStartsTheSession() {
        var serverValue = new AtomicReference<>("already available");
        var clientValue = new AtomicReference<>("");
        SyncSchema<Object> schema = SyncSchema.builder("test:server-push", 1)
                .field("value", SyncCodecs.STRING, menu -> serverValue.get(), (menu, value) -> clientValue.set(value))
                .build();
        try (var server = new TestBindings.Binding<>(null, schema);
                var client = new TestBindings.Binding<>(null, schema)) {
            server.serverStart();
            client.clientStart();
            server.serverPump(CodecScope.NONE, 42, batch -> {
                client.receive(CodecScope.NONE, batch);
                server.acknowledge(batch.revision(), batch.index());
            });
            assertEquals("already available", clientValue.get());
            assertTrue(client.hasSnapshot());
            assertEquals(1, client.statistics().revision());
            assertEquals(SyncStatus.READY, server.status());
        }
    }

    @Test
    void codecWorkRunsInsideTheGivenScope() {
        var entered = new AtomicInteger();
        var depth = new AtomicInteger();
        CodecScope scope = () -> {
            entered.incrementAndGet();
            depth.incrementAndGet();
            return depth::decrementAndGet;
        };
        var seenDepth = new AtomicInteger(-1);
        SyncSchema<Object> schema = SyncSchema.builder("test:scope", 1)
                .field(
                        "value",
                        SyncCodecs.INT,
                        menu -> {
                            seenDepth.set(depth.get());
                            return 1;
                        },
                        (menu, value) -> {})
                .build();
        try (var server = new TestBindings.Binding<>(null, schema)) {
            server.serverStart();
            server.serverPump(scope, 0, batch -> {});
            assertEquals(1, entered.get());
            assertEquals(1, seenDepth.get());
            assertEquals(0, depth.get());
        }
    }
}
