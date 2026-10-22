package dev.compixel.gradle;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Copies a JAR with every entry stored instead of compressed. A JAR embedded this way is compressed by the JAR around
 * it as one stream, which also finds what its files have in common, such as the names and constants that class files
 * share. The copy keeps the original's entries, order and times.
 */
public final class StoredJar {
    private StoredJar() {}

    public static void store(File source, File target) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        try (var in = new ZipFile(source);
                var out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target.toPath())))) {
            for (var entry : Collections.list(in.entries())) {
                byte[] data;
                try (var stream = in.getInputStream(entry)) {
                    data = stream.readAllBytes();
                }
                var crc = new CRC32();
                crc.update(data);
                var copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                copy.setMethod(ZipEntry.STORED);
                copy.setSize(data.length);
                copy.setCompressedSize(data.length);
                copy.setCrc(crc.getValue());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
    }

    /** Checks that {@code copy} holds the entries of {@code original} in the same order, all stored. */
    public static void verify(File original, File copy) throws IOException {
        try (var expected = new ZipFile(original);
                var actual = new ZipFile(copy)) {
            var names = Collections.list(expected.entries());
            var copies = Collections.list(actual.entries());
            if (names.size() != copies.size())
                throw new IOException(copy.getName() + " has other entries than " + original);
            for (int i = 0; i < names.size(); i++) {
                var entry = names.get(i);
                var stored = copies.get(i);
                if (!entry.getName().equals(stored.getName()) || stored.getMethod() != ZipEntry.STORED)
                    throw new IOException(copy.getName() + " differs from " + original + " at " + entry.getName());
                try (var a = expected.getInputStream(entry);
                        var b = actual.getInputStream(stored)) {
                    if (!Arrays.equals(a.readAllBytes(), b.readAllBytes()))
                        throw new IOException(copy.getName() + " differs from " + original + " at " + entry.getName());
                }
            }
        }
    }
}
