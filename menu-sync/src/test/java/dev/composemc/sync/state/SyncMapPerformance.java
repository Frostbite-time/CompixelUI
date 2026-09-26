package dev.composemc.sync.state;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Optional diagnostic, deliberately excluded from timing-sensitive pass/fail checks. */
public final class SyncMapPerformance {
    record Row(int id, long value) {}

    static final class Model {
        List<Row> list = List.of();
        SyncMap<Integer, Row> map = SyncMap.empty();
    }

    private static final SyncCodec<Row> CODEC = SyncCodec.of(
            "profile:row/1",
            (out, v) -> {
                out.writeInt(v.id());
                out.writeLong(v.value());
            },
            in -> new Row(in.readInt(), in.readLong()));
    private static long tick;

    private static void transfer(
            Model server, Model client, SyncPublisher<Model> sender, SyncReceiver<Model> receiver) {
        long wanted = sender.revision() + 1;
        while (sender.revision() < wanted) {
            long now = ++tick;
            sender.pump(server, now, b -> {
                receiver.accept(client, b, now);
                sender.acknowledge(b.revision(), b.index(), now);
            });
        }
    }

    public static void main(String[] args) throws Exception {
        boolean mapped = args[0].equals("map");
        var memory = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        var schema = mapped
                ? SyncSchema.<Model>builder("profile:menu", 1)
                        .keyedMap("rows", SyncCodecs.INT, CODEC, Row::id, m -> m.map, (m, v) -> m.map = v)
                        .build()
                : SyncSchema.<Model>builder("profile:menu", 1)
                        .keyedCollection("rows", SyncCodecs.INT, CODEC, Row::id, m -> m.list, (m, v) -> m.list = v)
                        .build();
        var rows = new ArrayList<String>();
        for (int size : new int[] {10_000, 100_000}) {
            var server = new Model();
            var client = new Model();
            var initial = new ArrayList<Row>();
            for (int i = 0; i < size; i++) {
                var row = new Row(i, 1);
                if (mapped) server.map = server.map.with(i, row);
                else initial.add(row);
            }
            if (!mapped) server.list = List.copyOf(initial);
            long[] times = new long[50], allocated = new long[50];
            long wire;
            try (var sender = new SyncPublisher<>(schema);
                    var receiver = new SyncReceiver<>(schema)) {
                transfer(server, client, sender, receiver);
                long initialBytes = sender.sentBytes();
                for (int i = 0; i < 70; i++) {
                    int index = i * 997 % size;
                    long
                            bytes =
                                    memory.getThreadAllocatedBytes(
                                            Thread.currentThread().getId()),
                            start = System.nanoTime();
                    var row = new Row(index, 100 + i);
                    if (mapped) server.map = server.map.with(index, row);
                    else {
                        var next = new ArrayList<>(server.list);
                        next.set(index, row);
                        server.list = List.copyOf(next);
                    }
                    transfer(server, client, sender, receiver);
                    long elapsed = System.nanoTime() - start,
                            allocation =
                                    memory.getThreadAllocatedBytes(
                                                    Thread.currentThread().getId())
                                            - bytes;
                    if (i >= 20) {
                        times[i - 20] = elapsed;
                        allocated[i - 20] = allocation;
                    }
                    if (!row.equals(mapped ? client.map.get(index) : client.list.get(index)))
                        throw new AssertionError("Update lost");
                }
                wire = (sender.sentBytes() - initialBytes) / 70;
            }
            Arrays.sort(times);
            Arrays.sort(allocated);
            rows.add("{\"entries\":" + size + ",\"samples\":50,\"roundTripP50Nanos\":" + times[24]
                    + ",\"roundTripP95Nanos\":" + times[47] + ",\"allocatedP50Bytes\":" + allocated[24]
                    + ",\"deltaDataBytes\":" + wire + "}");
        }
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "[\n" + String.join(",\n", rows) + "\n]\n");
        System.out.println("Profile written: " + output);
    }
}
