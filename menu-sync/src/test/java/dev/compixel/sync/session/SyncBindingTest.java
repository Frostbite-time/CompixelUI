package dev.compixel.sync.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.MenuSyncOptions;
import dev.compixel.sync.action.ActionLimits;
import dev.compixel.sync.state.SyncCodecs;
import dev.compixel.sync.state.SyncLimits;
import dev.compixel.sync.state.SyncPublisher;
import dev.compixel.sync.state.SyncSchema;
import dev.compixel.sync.transport.TokenBucket;
import dev.compixel.sync.transport.TransferBudget;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SyncBindingTest {
    private static final SyncSchema<Object> OPTIONS_SCHEMA = SyncSchema.builder("test:options", 1)
            .field("v", SyncCodecs.INT, m -> 1, (m, v) -> {})
            .build();

    private static SyncSchema<Object> text(AtomicReference<String> text) {
        return SyncSchema.builder("test:readiness", 1)
                .field("text", SyncCodecs.string(1024), m -> text.get(), (m, v) -> text.set(v))
                .build();
    }

    @Test
    void wirePolicyJoinsFingerprintButLocalLoggingDoesNot() {
        try (var standard = new TestBindings.Binding<>(null, OPTIONS_SCHEMA);
                var quiet = new TestBindings.Binding<>(
                        null, OPTIONS_SCHEMA, MenuSyncOptions.DEFAULT.withRejectionLogInterval(-1))) {
            assertEquals(standard.fingerprint(), quiet.fingerprint());
            var actions = new ActionLimits(2048, TransferBudget.steady(256), 10_000, 100, 50, 900, 800, 700);
            try (var different =
                    new TestBindings.Binding<>(null, OPTIONS_SCHEMA, MenuSyncOptions.DEFAULT.withActions(actions))) {
                assertNotEquals(standard.fingerprint(), different.fingerprint());
            }
        }
    }

    @Test
    void declaredActionsJoinTheFingerprintAndCloseWithTheSession() {
        try (var binding = new TestBindings.Binding<Object, Object>(null, OPTIONS_SCHEMA)) {
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
        var limits = new SyncLimits(64, TransferBudget.steady(64), 2, 2048, 4096, 16, 100);
        try (var publisher = new SyncPublisher<>(text(server), limits);
                var binding =
                        new TestBindings.Binding<>(null, text(client), MenuSyncOptions.DEFAULT.withState(limits))) {
            binding.onUpdate(m -> publications.incrementAndGet());
            binding.clientStart();
            assertFalse(binding.hasSnapshot());
            publisher.pump(null, 0, batch -> {
                binding.receive(CodecScope.NONE, batch, 0);
                publisher.acknowledge(batch.revision(), batch.index(), 0);
            });
            assertTrue(binding.hasSnapshot());
            assertEquals("initial", client.get());
            assertEquals(1, publications.get());
            server.set("x".repeat(300));
            publisher.pump(null, 1, batch -> {
                binding.receive(CodecScope.NONE, batch, 1);
                publisher.acknowledge(batch.revision(), batch.index(), 1);
            });
            assertEquals(SyncStatus.SYNCING, binding.status());
            assertTrue(binding.hasSnapshot());
            assertEquals("initial", client.get());
            assertEquals(1, publications.get());
            for (int tick = 2; tick < 20 && publisher.busy(); tick++) {
                int now = tick;
                publisher.pump(null, now, batch -> {
                    binding.receive(CodecScope.NONE, batch, now);
                    publisher.acknowledge(batch.revision(), batch.index(), now);
                });
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
                .field(
                        "value",
                        SyncCodecs.string(128),
                        menu -> serverValue.get(),
                        (menu, value) -> clientValue.set(value))
                .build();
        var limits = new SyncLimits(1024, TransferBudget.steady(1024), 2, 2048, 4096, 16, 100);
        var options = MenuSyncOptions.DEFAULT.withState(limits);
        try (var server = new TestBindings.Binding<>(null, schema, options);
                var client = new TestBindings.Binding<>(null, schema, options)) {
            server.serverStart(new TokenBucket(limits.bandwidth(), 42));
            client.clientStart();
            server.serverPump(CodecScope.NONE, 42, () -> true, batch -> {
                client.receive(CodecScope.NONE, batch, 42);
                server.acknowledge(batch.revision(), batch.index(), 42);
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
            server.serverStart(new TokenBucket(SyncLimits.DEFAULT.bandwidth(), 0));
            server.serverPump(scope, 0, () -> true, batch -> {});
            assertEquals(1, entered.get());
            assertEquals(1, seenDepth.get());
            assertEquals(0, depth.get());
        }
    }
}
