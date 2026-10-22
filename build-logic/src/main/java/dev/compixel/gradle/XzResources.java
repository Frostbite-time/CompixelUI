package dev.compixel.gradle;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/** Writes each file under a directory xz-compressed as {@code <name>.xz}, for resources that ship compressed. */
public final class XzResources {
    private XzResources() {}

    public static void compress(File sourceRoot, File outputRoot) throws IOException {
        var source = sourceRoot.toPath();
        var output = outputRoot.toPath();
        if (Files.exists(output)) {
            try (var stale = Files.walk(output)) {
                for (var path : stale.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        try (var files = Files.walk(source)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                Path target = output.resolve(source.relativize(file) + ".xz");
                Files.createDirectories(target.getParent());
                try (var out = new XZOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(target)), new LZMA2Options(6))) {
                    Files.copy(file, out);
                }
            }
        }
    }
}
