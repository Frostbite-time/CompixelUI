package dev.compixel.sync.state;

import static org.junit.jupiter.api.Assertions.*;

import dev.compixel.sync.SyncException;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MenuSyncTest {
    record Row(int id, String name) {}

    static class Model {
        int selected = -1, publications;
        List<Row> rows = List.of();
        String text = "";
    }

    static final AtomicInteger writes = new AtomicInteger();
    static final SyncCodec<Row> ROW = SyncCodec.of(
            "test:row/1",
            (out, row) -> {
                writes.incrementAndGet();
                out.writeInt(row.id);
                SyncCodecs.STRING.write(out, row.name);
            },
            in -> new Row(in.readInt(), SyncCodecs.STRING.read(in)));
    static final SyncSchema<Model> SCHEMA = SyncSchema.<Model>builder("test:menu", 1)
            .field("selected", SyncCodecs.INT, m -> m.selected, (m, v) -> m.selected = v)
            .keyedCollection("rows", SyncCodecs.INT, ROW, Row::id, m -> m.rows, (m, v) -> {
                m.rows = v;
                m.publications++;
            })
            .build();

    static List<Row> rows(int count) {
        return IntStream.range(0, count).mapToObj(i -> new Row(i, "网络 " + i)).toList();
    }

    /** Pumps once per tick and delivers and acknowledges every batch at once, through the wire format. */
    static long transfer(Model server, Model client, SyncPublisher<Model> tx, SyncReceiver<Model> rx, long tick) {
        long expected = rx.revision() + 1;
        for (; tick < 10_000; tick++) {
            tx.pump(server, tick, batch -> {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    batch.write(new DataOutputStream(bytes));
                    assertTrue(batch.dataSize() <= SyncPublisher.BATCH_BYTES);
                    SyncBatch decoded = SyncBatch.read(
                            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                            SyncPublisher.BATCH_BYTES);
                    int before = client.publications;
                    rx.accept(client, decoded);
                    if (!batch.last()) assertEquals(before, client.publications);
                    tx.acknowledge(batch.revision(), batch.index());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (rx.revision() == expected && !tx.busy()) return tick + 1;
        }
        fail("Transfer did not finish");
        return 0;
    }

    @Test
    void largeSnapshotThenSmallDeltaAndIdleDoNotResendAllRows() {
        Model server = new Model(), client = new Model();
        server.rows = rows(100_000);
        server.selected = 4;
        try (var tx = new SyncPublisher<>(SCHEMA);
                var rx = new SyncReceiver<>(SCHEMA)) {
            long tick = transfer(server, client, tx, rx, 0);
            assertEquals(server.rows, client.rows);
            assertEquals(4, client.selected);
            assertEquals(1, client.publications);
            assertTrue(tx.sentBatches() > 1);
            long bytes = tx.sentBytes(), ops = tx.sentOperations();
            List<Row> changed = new ArrayList<>(server.rows);
            changed.set(4, new Row(4, "Renamed"));
            changed.remove(8);
            changed.add(new Row(100_001, "Added"));
            server.rows = List.copyOf(changed);
            server.selected = 5;
            tick = transfer(server, client, tx, rx, tick);
            assertEquals(server.rows, client.rows);
            assertEquals(5, client.selected);
            assertEquals(2, client.publications);
            assertEquals(4, tx.sentOperations() - ops);
            assertTrue(tx.sentBytes() - bytes < 256);
            int before = writes.get();
            for (int i = 0; i < 100; i++) tx.pump(server, tick + i, b -> fail("Idle state sent a batch"));
            assertEquals(before, writes.get());
        }
    }

    @Test
    void unacknowledgedBatchesStayWithinTheWindowAndChangesCoalesce() {
        Model server = new Model(), client = new Model();
        server.rows = rows(40_000);
        int window = SyncPublisher.WINDOW_BYTES / SyncPublisher.BATCH_BYTES;
        List<SyncBatch> pending = new ArrayList<>();
        try (var tx = new SyncPublisher<>(SCHEMA);
                var rx = new SyncReceiver<>(SCHEMA)) {
            tx.pump(server, 0, pending::add);
            assertEquals(window, pending.size());
            assertEquals(SyncPublisher.WINDOW_BYTES, tx.inFlightBytes());
            // Once per tick, and nothing more until the receiver acknowledges.
            tx.pump(server, 0, pending::add);
            tx.pump(server, 1, pending::add);
            assertEquals(window, pending.size());
            for (int i = 0; i < 2; i++) assertEquals(SyncReceiver.Result.STAGED, rx.accept(client, pending.get(i)));
            tx.acknowledge(1, 1);
            assertEquals(SyncPublisher.WINDOW_BYTES - 2L * SyncPublisher.BATCH_BYTES, tx.inFlightBytes());
            tx.pump(server, 2, pending::add);
            assertEquals(window + 2, pending.size());
            server.selected = 77;
            long tick = 3;
            int delivered = 2;
            while (client.selected != 77) {
                for (; delivered < pending.size(); delivered++) {
                    var batch = pending.get(delivered);
                    rx.accept(client, batch);
                    tx.acknowledge(batch.revision(), batch.index());
                }
                tx.pump(server, tick++, pending::add);
                assertTrue(tick < 1000);
            }
            // The change made during the snapshot followed it as the next revision.
            assertEquals(2, rx.revision());
            assertEquals(server.rows, client.rows);
            assertEquals(0, tx.inFlightBytes());
        }
    }

    @Test
    void duplicateTruncatedAndReorderedBatchesNeverPublishPartialData() {
        Model server = new Model(), client = new Model();
        server.rows = rows(15_000);
        List<SyncBatch> packets = new ArrayList<>();
        try (var tx = new SyncPublisher<>(SCHEMA);
                var rx = new SyncReceiver<>(SCHEMA)) {
            tx.pump(server, 0, packets::add);
            assertTrue(packets.size() >= 3);
            assertEquals(SyncReceiver.Result.STAGED, rx.accept(client, packets.get(0)));
            assertEquals(SyncReceiver.Result.DUPLICATE, rx.accept(client, packets.get(0)));
            assertThrows(SyncException.class, () -> rx.accept(client, packets.get(2)));
            SyncBatch earlyEnd = new SyncBatch(
                    1,
                    true,
                    1,
                    packets.get(1).operations(),
                    true,
                    packets.get(1).data());
            assertThrows(SyncException.class, () -> rx.accept(client, earlyEnd));
            assertTrue(client.rows.isEmpty());
            assertEquals(-1, client.selected);
            assertEquals(0, client.publications);
        }
    }

    @Test
    void malformedRecordLengthsAndTruncatedWireBatchesAreRejected() throws IOException {
        Model client = new Model();
        SyncBatch malformed = new SyncBatch(1, true, 0, 1, true, new byte[] {0, 0, 0, 1, 0});
        try (var rx = new SyncReceiver<>(SCHEMA)) {
            assertThrows(SyncException.class, () -> rx.accept(client, malformed));
            assertEquals(0, client.publications);
            assertEquals(0, rx.revision());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        malformed.write(new DataOutputStream(bytes));
        byte[] truncated = Arrays.copyOf(bytes.toByteArray(), bytes.size() - 1);
        assertThrows(
                EOFException.class,
                () -> SyncBatch.read(
                        new DataInputStream(new ByteArrayInputStream(truncated)), SyncPublisher.BATCH_BYTES));
    }

    @Test
    void declaredBatchAndRecordLengthsAllocateOnlyForReceivedBytes() throws Exception {
        int declared = 64 * 1024 * 1024;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        var out = new DataOutputStream(frame);
        out.writeLong(1);
        out.writeBoolean(true);
        out.writeInt(0);
        out.writeInt(1);
        out.writeBoolean(false);
        out.writeInt(declared);
        // Link each rejection path first so one-time bootstrap work is not mistaken for decoding memory.
        assertThrows(
                EOFException.class,
                () -> SyncBatch.read(new DataInputStream(new ByteArrayInputStream(frame.toByteArray())), declared));
        var wire = new DataInputStream(new ByteArrayInputStream(frame.toByteArray()));
        long batch = Allocations.measure(() -> assertThrows(EOFException.class, () -> SyncBatch.read(wire, declared)));
        assertTrue(batch < 1024 * 1024, "A truncated batch declaring 64 MiB allocated " + batch + " bytes");

        var header = new SyncBatch(
                1, true, 0, 1, false, ByteBuffer.allocate(4).putInt(declared).array());
        try (var warm = new SyncReceiver<>(SCHEMA)) {
            warm.accept(new Model(), header);
        }
        try (var rx = new SyncReceiver<>(SCHEMA)) {
            var client = new Model();
            long record =
                    Allocations.measure(() -> assertEquals(SyncReceiver.Result.STAGED, rx.accept(client, header)));
            assertTrue(record < 1024 * 1024, "A record header declaring 64 MiB allocated " + record + " bytes");
        }
    }

    @Test
    void oneLargeValueIsFragmentedAndPublishedOnce() {
        SyncSchema<Model> schema = SyncSchema.<Model>builder("test:large", 1)
                .field("text", SyncCodecs.STRING, m -> m.text, (m, v) -> m.text = v)
                .build();
        Model server = new Model(), client = new Model();
        // Larger than a window and than any single Minecraft payload: about 3 MB.
        server.text = "中文😀".repeat(300_000);
        try (var tx = new SyncPublisher<>(schema);
                var rx = new SyncReceiver<>(schema)) {
            transfer(server, client, tx, rx, 0);
            assertEquals(server.text, client.text);
            assertTrue(tx.sentBytes() > 2L * SyncPublisher.WINDOW_BYTES);
        }
    }

    @Test
    void duplicateKeysAreRejectedBeforeTransport() {
        Model duplicate = new Model();
        duplicate.rows = List.of(new Row(1, "a"), new Row(1, "b"));
        try (var tx = new SyncPublisher<>(SCHEMA)) {
            assertThrows(SyncException.class, () -> tx.pump(duplicate, 0, b -> {}));
        }
    }

    @Test
    void staleRevisionAndClosedReceiverCannotApplyData() {
        Model server = new Model(), client = new Model();
        server.rows = rows(2);
        List<SyncBatch> packets = new ArrayList<>();
        try (var tx = new SyncPublisher<>(SCHEMA);
                var rx = new SyncReceiver<>(SCHEMA)) {
            tx.pump(server, 0, packets::add);
            SyncBatch packet = packets.get(0);
            assertEquals(SyncReceiver.Result.COMMITTED, rx.accept(client, packet));
            assertEquals(SyncReceiver.Result.STALE, rx.accept(client, packet));
            assertEquals(1, client.publications);
            rx.close();
            assertThrows(IllegalStateException.class, () -> rx.accept(client, packet));
            tx.close();
            tx.pump(server, 3, b -> fail("Closed publisher sent data"));
        }
    }

    @Test
    void scalarUpdatesKeepThePublishedCollectionAndClearingUsesOneOperation() {
        Model server = new Model(), client = new Model();
        server.rows = rows(10_000);
        try (var tx = new SyncPublisher<>(SCHEMA);
                var rx = new SyncReceiver<>(SCHEMA)) {
            long tick = transfer(server, client, tx, rx, 0);
            List<Row> published = client.rows;
            long bytes = tx.sentBytes(), ops = tx.sentOperations();
            server.selected = 99;
            tick = transfer(server, client, tx, rx, tick);
            assertSame(published, client.rows);
            assertEquals(1, client.publications);
            assertEquals(1, tx.sentOperations() - ops);
            assertEquals(13, tx.sentBytes() - bytes);
            ops = tx.sentOperations();
            server.rows = List.of();
            transfer(server, client, tx, rx, tick);
            assertTrue(client.rows.isEmpty());
            assertEquals(1, tx.sentOperations() - ops);
        }
    }

    @Test
    void staleBatchDuringANewerRevisionDoesNotEndThePendingTransaction() {
        Model server = new Model(), client = new Model();
        var first = new ArrayList<SyncBatch>();
        try (var tx = new SyncPublisher<>(SCHEMA);
                var rx = new SyncReceiver<>(SCHEMA)) {
            tx.pump(server, 0, b -> {
                first.add(b);
                rx.accept(client, b);
                tx.acknowledge(b.revision(), b.index());
            });
            server.rows = rows(20_000);
            var next = new ArrayList<SyncBatch>();
            tx.pump(server, 1, next::add);
            rx.accept(client, next.get(0));
            assertEquals(SyncReceiver.Result.STALE, rx.accept(client, first.get(0)));
            assertTrue(rx.receiving());
            assertTrue(client.rows.isEmpty());
        }
    }

    @Test
    void failedClientSetterRollsBackAssignmentsAndInvalidUnicodeIsRejected() {
        SyncSchema<Model> schema = SyncSchema.<Model>builder("test:rollback", 1)
                .field("selected", SyncCodecs.INT, m -> m.selected, (m, v) -> m.selected = v)
                .field("text", SyncCodecs.STRING, m -> m.text, (m, v) -> {
                    if (v.equals("fail")) throw new IllegalStateException("setter");
                    m.text = v;
                })
                .build();
        Model server = new Model(), client = new Model();
        server.selected = 99;
        server.text = "fail";
        try (var tx = new SyncPublisher<>(schema);
                var rx = new SyncReceiver<>(schema)) {
            assertThrows(SyncException.class, () -> transfer(server, client, tx, rx, 0));
            assertEquals(-1, client.selected);
            assertEquals("", client.text);
        }
        assertThrows(
                IOException.class,
                () -> SyncCodecs.STRING.write(new DataOutputStream(new ByteArrayOutputStream()), "\uD83D"));
    }

    @Test
    void realLoopbackTcpUsesTheProductionBatchCodec() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> {
                Model server = new Model();
                server.rows = rows(100_000);
                server.selected = 12;
                try (Socket socket = listener.accept();
                        var tx = new SyncPublisher<>(SCHEMA)) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    for (long tick = 0; tx.revision() == 0; tick++) {
                        AtomicInteger sent = new AtomicInteger();
                        tx.pump(server, tick, b -> {
                            try {
                                b.write(out);
                                out.flush();
                                sent.incrementAndGet();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                        for (int i = 0; i < sent.get(); i++) tx.acknowledge(in.readLong(), in.readInt());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Model client = new Model();
            try (Socket socket = new Socket(listener.getInetAddress(), listener.getLocalPort());
                    var rx = new SyncReceiver<>(SCHEMA)) {
                socket.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                while (rx.revision() == 0) {
                    SyncBatch b = SyncBatch.read(in, SyncPublisher.BATCH_BYTES);
                    rx.accept(client, b);
                    out.writeLong(b.revision());
                    out.writeInt(b.index());
                    out.flush();
                }
            }
            sending.get(10, TimeUnit.SECONDS);
            assertEquals(100_000, client.rows.size());
            assertEquals(12, client.selected);
        }
    }
}
