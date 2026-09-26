package dev.composemc.sync.state;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.composemc.sync.SyncException;
import dev.composemc.sync.transport.TransferBudget;

class SyncMapTest {
    record Key(int id) { @Override public int hashCode() { return id % 7 == 0 ? Integer.MIN_VALUE : id % 11; } }

    @Test void immutableSnapshotsAndDifferencesMatchIndependentMapsThroughCollisionsAndRotations() {
        SyncMap<Key, Integer> current = SyncMap.empty();
        var expected = new HashMap<Key, Integer>(); var random = new Random(1321);
        for (int iteration = 0; iteration < 400; iteration++) {
            var previous = current; var before = new HashMap<>(expected);
            for (int n = 0; n < 17; n++) {
                var key = new Key(random.nextInt(180));
                if (random.nextBoolean()) { int value = random.nextInt(1000); expected.put(key, value); current = current.with(key, value); }
                else { expected.remove(key); current = current.without(key); }
            }
            assertEquals(before, previous); assertEquals(expected, current);
            var applied = new HashMap<>(before); var visited = new HashSet<Key>();
            current.forEachChange(previous, (key, old, value) -> {
                assertTrue(visited.add(key)); assertEquals(before.get(key), old); assertNotEquals(old, value);
                if (value == null) applied.remove(key); else applied.put(key, value);
            });
            assertEquals(expected, applied);
        }
        var same = current;
        for (var entry : current.entrySet()) assertSame(same, current.with(entry.getKey(), entry.getValue()));
        assertThrows(UnsupportedOperationException.class, () -> same.put(new Key(1000), 1));
        assertThrows(UnsupportedOperationException.class, () -> same.entrySet().iterator().next().setValue(9));
        assertThrows(NullPointerException.class, () -> same.with(new Key(1), null));
        assertThrows(NullPointerException.class, () -> same.with(null, 1));
    }

    @Test void sortedAndReverseInsertionsRetainLookupAndDeletionSemantics() {
        SyncMap<Integer, Integer> sorted = SyncMap.empty(), reverse = SyncMap.empty();
        for (int i = 0; i < 20000; i++) { sorted = sorted.with(i, i); reverse = reverse.with(19999 - i, 19999 - i); }
        assertEquals(sorted, reverse);
        var base = sorted;
        for (int i = 0; i < 20000; i += 2) sorted = sorted.without(i);
        assertEquals(10000, sorted.size()); assertEquals(20000, base.size());
        var deleted = new HashSet<Integer>(); sorted.forEachChange(base, (key, before, after) -> { assertNull(after); assertEquals(key, before); deleted.add(key); });
        assertEquals(10000, deleted.size());
        for (int i = 1; i < 20000; i += 2) { assertEquals(i, sorted.get(i)); sorted = sorted.without(i); }
        assertSame(SyncMap.empty(), sorted);
    }

    record Row(int id, String text) {}
    static final class Model { SyncMap<Integer, Row> rows = SyncMap.empty(); }
    static final SyncCodec<Row> CODEC = SyncCodec.of("test:map_row/1", (out, value) -> { out.writeInt(value.id()); SyncCodecs.string(1024).write(out, value.text()); },
            in -> new Row(in.readInt(), SyncCodecs.string(1024).read(in)));
    static final SyncSchema<Model> SCHEMA = SyncSchema.<Model>builder("test:map", 1)
            .keyedMap("rows", SyncCodecs.INT, CODEC, Row::id, model -> model.rows, (model, values) -> model.rows = values).build();

    @Test void mapUpdatesStayAtomicAndReplaceAtCapacityWithoutResendingUnchangedValues() {
        var limits = new SyncLimits(96, dev.composemc.sync.transport.TransferBudget.steady(96), 2, 2048, 1024 * 1024, 1000, 100);
        var server = new Model(); var client = new Model();
        for (int i = 0; i < 1000; i++) server.rows = server.rows.with(i, new Row(i, "initial"));
        try (var sender = new SyncPublisher<>(SCHEMA, limits); var receiver = new SyncReceiver<>(SCHEMA, limits)) {
            long tick = 0;
            while (sender.revision() == 0) { long now = ++tick; sender.pump(server, now, batch -> { receiver.accept(client, batch, now); sender.acknowledge(batch.revision(), batch.index(), now); }); }
            var before = client.rows;
            long operations = sender.sentOperations(), bytes = sender.sentBytes();
            server.rows = server.rows.without(0).with(1000, new Row(1000, "new")).with(1, new Row(1, "x".repeat(600)));
            long first = ++tick;
            sender.pump(server, first, batch -> { receiver.accept(client, batch, first); sender.acknowledge(batch.revision(), batch.index(), first); });
            assertTrue(receiver.receiving()); assertSame(before, client.rows); assertEquals("initial", before.get(1).text());
            while (sender.revision() < 2) { long now = ++tick; sender.pump(server, now, batch -> { receiver.accept(client, batch, now); sender.acknowledge(batch.revision(), batch.index(), now); }); }
            assertEquals(server.rows, client.rows); assertEquals(1000, client.rows.size());
            assertEquals(3, sender.sentOperations() - operations); assertTrue(sender.sentBytes() - bytes < 1024);
            assertSame(before.get(500), client.rows.get(500)); assertTrue(before.containsKey(0));
        }
    }

    @Test void includedMapsKeepTheirTypeAndRejectMismatchedKeysBeforeTransport() {
        record Parent(Model child) {}
        var schema = SyncSchema.<Parent>builder("test:parent", 1).include("child", SCHEMA, Parent::child).build();
        var server = new Parent(new Model()); var client = new Parent(new Model());
        server.child.rows = server.child.rows.with(2, new Row(2, "value"));
        try (var sender = new SyncPublisher<>(schema); var receiver = new SyncReceiver<>(schema)) {
            sender.pump(server, 0, batch -> { receiver.accept(client, batch, 0); sender.acknowledge(batch.revision(), batch.index(), 0); });
            assertEquals(server.child.rows, client.child.rows);
            server.child.rows = server.child.rows.with(3, new Row(4, "mismatch"));
            assertThrows(SyncException.class, () -> sender.pump(server, 1, batch -> fail("Invalid data reached transport")));
        }
    }
}
