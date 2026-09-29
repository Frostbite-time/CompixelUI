package dev.compixel.sync.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SchemaCompositionTest {
    @Test
    void fieldIndexesAboveOneByteRoundTripWithoutAnArbitraryDeclarationCap() {
        var builder = SyncSchema.<int[]>builder("test:many_fields", 1);
        for (int i = 0; i < 300; i++) {
            int index = i;
            builder.field("field_" + i, SyncCodecs.INT, m -> m[index], (m, v) -> m[index] = v);
        }
        var schema = builder.build();
        int[] source = java.util.stream.IntStream.range(0, 300).toArray(), target = new int[300];
        try (var tx = new SyncPublisher<>(schema);
                var rx = new SyncReceiver<>(schema)) {
            tx.pump(source, 0, batch -> {
                rx.accept(target, batch, 0);
                tx.acknowledge(batch.revision(), batch.index(), 0);
            });
            assertArrayEquals(source, target);
        }
    }

    static class Part {
        int value;
        List<Integer> rows = List.of();
    }

    static class Whole {
        Part input = new Part(), output = new Part();
    }

    private static SyncSchema<Part> part(int version) {
        return SyncSchema.<Part>builder("test:part", version)
                .field("value", SyncCodecs.INT, m -> m.value, (m, v) -> m.value = v)
                .keyedCollection("rows", SyncCodecs.INT, SyncCodecs.INT, v -> v, m -> m.rows, (m, v) -> m.rows = v)
                .build();
    }

    @Test
    void nestedNamespacesSynchronizeIndependentlyAndKeepUnchangedCollections() {
        var schema = SyncSchema.<Whole>builder("test:whole", 1)
                .include("input", part(1), m -> m.input)
                .include("output", part(1), m -> m.output)
                .build();
        var server = new Whole();
        server.input.value = 3;
        server.input.rows = List.of(1, 2);
        server.output.value = 7;
        server.output.rows = List.of(8, 9);
        var client = new Whole();
        try (var tx = new SyncPublisher<>(schema);
                var rx = new SyncReceiver<>(schema)) {
            tx.pump(server, 0, b -> {
                rx.accept(client, b, 0);
                tx.acknowledge(b.revision(), b.index(), 0);
            });
            assertEquals(3, client.input.value);
            assertEquals(7, client.output.value);
            assertEquals(List.of(8, 9), client.output.rows);
            var untouched = client.output.rows;
            server.input.rows = List.of(2, 3);
            server.input.value = 5;
            tx.pump(server, 1, b -> {
                rx.accept(client, b, 1);
                tx.acknowledge(b.revision(), b.index(), 1);
            });
            assertEquals(List.of(2, 3), client.input.rows);
            assertEquals(5, client.input.value);
            assertSame(untouched, client.output.rows);
        }
    }

    @Test
    void includedVersionsAffectIdentityAndConflictsDoNotPartiallyModifyBuilders() {
        var first = SyncSchema.<Whole>builder("test:whole", 1)
                .include("input", part(1), m -> m.input)
                .build();
        var next = SyncSchema.<Whole>builder("test:whole", 1)
                .include("input", part(2), m -> m.input)
                .build();
        assertNotEquals(first.fingerprint(), next.fingerprint());
        var builder =
                SyncSchema.<Whole>builder("test:whole", 1).field("input.rows", SyncCodecs.INT, m -> 1, (m, v) -> {});
        String before = builder.build().fingerprint();
        assertThrows(IllegalArgumentException.class, () -> builder.include("input", part(1), m -> m.input));
        assertEquals(before, builder.build().fingerprint());
    }
}
