package dev.compixel.gradle;

import java.io.*;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Keeps Kotlin-dependent libraries linkable with any Kotlin runtime provider. */
public final class NestedLibraryJar {
    private NestedLibraryJar() {}

    public static void prepare(File source, File destination) throws IOException {
        var descriptors = ModuleFinder.of(source.toPath()).findAll();
        var descriptor = descriptors.size() == 1 ? descriptors.iterator().next().descriptor() : null;
        boolean requiresKotlin = descriptor != null
                && descriptor.requires().stream()
                        .anyMatch(r ->
                                r.name().equals("kotlin.stdlib") || r.name().startsWith("kotlinx.coroutines"));
        if (!requiresKotlin) {
            Files.copy(source.toPath(), destination.toPath());
            return;
        }
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new JarFile(source)) {
            for (var entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || name.equals("META-INF/MANIFEST.MF")
                        || name.equals("module-info.class")
                        || name.matches("META-INF/versions/\\d+/module-info\\.class")) continue;
                try (var stream = zip.getInputStream(entry)) {
                    entries.put(name, stream.readAllBytes());
                }
            }
        }
        // Automatic modules can read whichever Kotlin provider the host loads, even when it shades Kotlin.
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Automatic-Module-Name", descriptor.name());
        if (entries.keySet().stream().anyMatch(n -> n.startsWith("META-INF/versions/")))
            attributes.put(Attributes.Name.MULTI_RELEASE, "true");
        for (var service : descriptor.provides()) {
            String name = "META-INF/services/" + service.service();
            entries.computeIfAbsent(
                    name, k -> (String.join("\n", service.providers()) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        var header = new ByteArrayOutputStream();
        manifest.write(header);
        entries.put("META-INF/MANIFEST.MF", header.toByteArray());
        try (var zip = new ZipOutputStream(Files.newOutputStream(destination.toPath()))) {
            for (var entry : entries.entrySet()) {
                var item = new ZipEntry(entry.getKey());
                item.setTime(0);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}
