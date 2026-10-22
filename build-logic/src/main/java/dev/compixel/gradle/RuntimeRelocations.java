package dev.compixel.gradle;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Libraries that the runtime bundles carry under {@code dev.compixel.runtime.relocate}, so that another mod's copy of
 * them never shares a package with the bundle. {@link #relocate} moves them in the bundles and rewrites the references
 * that CompixelUI's own classes, compiled against their original names, make to them.
 */
public final class RuntimeRelocations {
    /** Original package to relocated package, each under {@code dev.compixel.runtime.relocate}. */
    public static final Map<String, String> PACKAGES = Map.of("org.tukaani.xz", "dev.compixel.runtime.relocate.xz");

    private static final Pattern VERSIONED = Pattern.compile("META-INF/versions/\\d+/");

    private RuntimeRelocations() {}

    /**
     * Moves the files of the relocated packages in {@code jar} to their new names, versioned classes included, and
     * makes every class refer to them there. A class spells each class name, descriptor, signature and string it uses
     * in its constant pool, so only constant pools change.
     */
    public static void relocate(File jar) throws IOException {
        var temporary = new File(jar.getPath() + ".relocating");
        boolean changed = false;
        try (var in = new ZipFile(jar);
                var out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary.toPath())))) {
            var entries = Collections.list(in.entries());
            var files = entries.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .toList();
            var relocatedFiles =
                    files.stream().map(RuntimeRelocations::relocatePath).toList();
            for (var entry : entries) {
                var name = relocatePath(entry.getName());
                changed |= !name.equals(entry.getName());
                // Directories that held only the original packages stay behind.
                if (entry.isDirectory()
                        && files.stream().anyMatch(file -> file.startsWith(name))
                        && relocatedFiles.stream().noneMatch(file -> file.startsWith(name))) {
                    continue;
                }
                byte[] data;
                try (var stream = in.getInputStream(entry)) {
                    data = stream.readAllBytes();
                }
                if (name.endsWith(".class")) {
                    var relocated = relocateClass(data);
                    changed |= relocated != data;
                    data = relocated;
                }
                var copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                copy.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    var crc = new CRC32();
                    crc.update(data);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                }
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        if (changed) Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        else Files.delete(temporary.toPath());
    }

    /** The class files in {@code jar} that still refer to a relocated package by its original name. */
    public static List<String> originalReferences(File jar) throws IOException {
        var found = new ArrayList<String>();
        try (var zip = new ZipFile(jar)) {
            for (var entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                String text;
                try (var stream = zip.getInputStream(entry)) {
                    text = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
                if (PACKAGES.keySet().stream().anyMatch(original -> text.contains(internalName(original)))) {
                    found.add(entry.getName());
                }
            }
        }
        return found;
    }

    private static byte[] relocateClass(byte[] data) throws IOException {
        if (data.length < 10 || (readU2(data, 0) << 16 | readU2(data, 2)) != 0xCAFEBABE) {
            throw new IOException("Not a class file");
        }
        var out = new ByteArrayOutputStream(data.length + 256);
        out.write(data, 0, 10);
        int count = readU2(data, 8);
        int position = 10;
        boolean changed = false;
        for (int index = 1; index < count; index++) {
            int tag = data[position] & 0xFF;
            int size;
            switch (tag) {
                case 1 -> size = 3 + readU2(data, position + 1);
                case 7, 8, 16, 19, 20 -> size = 3;
                case 15 -> size = 4;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> size = 5;
                case 5, 6 -> {
                    // Longs and doubles take two constant pool slots.
                    size = 9;
                    index++;
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
            if (tag == 1) {
                // Package names are ASCII, and modified UTF-8 encodes every other character with bytes of 0x80 and
                // above, so the names match as Latin-1 text without decoding the constant.
                var text = new String(data, position + 3, size - 3, StandardCharsets.ISO_8859_1);
                var relocated = text;
                for (var entry : PACKAGES.entrySet()) {
                    relocated = relocated
                            .replace(internalName(entry.getKey()), internalName(entry.getValue()))
                            .replace(entry.getKey() + ".", entry.getValue() + ".");
                }
                if (!relocated.equals(text)) {
                    var bytes = relocated.getBytes(StandardCharsets.ISO_8859_1);
                    if (bytes.length > 0xFFFF) throw new IOException("A relocated constant is too long");
                    changed = true;
                    out.write(1);
                    out.write(bytes.length >> 8);
                    out.write(bytes.length);
                    out.write(bytes, 0, bytes.length);
                    position += size;
                    continue;
                }
            }
            out.write(data, position, size);
            position += size;
        }
        if (!changed) return data;
        out.write(data, position, data.length - position);
        return out.toByteArray();
    }

    // A versioned file keeps its META-INF/versions/<n>/ prefix.
    private static String relocatePath(String path) {
        var versioned = VERSIONED.matcher(path);
        int start = versioned.lookingAt() ? versioned.end() : 0;
        for (var entry : PACKAGES.entrySet()) {
            var original = internalName(entry.getKey());
            if (path.startsWith(original, start)) {
                return path.substring(0, start)
                        + internalName(entry.getValue())
                        + path.substring(start + original.length());
            }
        }
        return path;
    }

    private static String internalName(String packageName) {
        return packageName.replace('.', '/') + "/";
    }

    private static int readU2(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
