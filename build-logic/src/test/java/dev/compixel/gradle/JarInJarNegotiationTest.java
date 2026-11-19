package dev.compixel.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class JarInJarNegotiationTest {
    @TempDir
    Path temporary;

    record Archive(String id, byte[] bytes) {}

    static Optional<InputStream> resource(Archive archive, String name) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive.bytes()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().equals(name)) return Optional.of(new ByteArrayInputStream(zip.readAllBytes()));
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static Optional<Archive> nested(Archive archive, String name) {
        return resource(archive, name).map(stream -> {
            try (stream) {
                return new Archive(archive.id() + "!/" + name, stream.readAllBytes());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<Archive> neo(List<Archive> roots) {
        // 0.4 uses Path and 0.5 uses String; both are resource names in the documented metadata format.
        java.util.function.BiFunction<Archive, Object, Optional<InputStream>> reader =
                (a, p) -> resource(a, p.toString().replace('\\', '/'));
        java.util.function.BiFunction<Archive, Object, Optional<Archive>> producer =
                (a, p) -> nested(a, p.toString().replace('\\', '/'));
        return net.neoforged.jarjar.selection.JarSelector.<Archive, IllegalStateException>detectAndSelect(
                roots,
                (java.util.function.BiFunction) reader,
                (java.util.function.BiFunction) producer,
                Archive::id,
                failures -> new IllegalStateException(failures.toString()));
    }

    static List<Archive> forge(List<Archive> roots) {
        return net.minecraftforge.jarjar.selection.JarSelector.detectAndSelect(
                roots,
                (a, p) -> resource(a, p.toString().replace('\\', '/')),
                (a, p) -> nested(a, p.toString().replace('\\', '/')),
                Archive::id,
                failures -> new IllegalStateException(failures.toString()));
    }

    static Archive embed(String owner, String artifact, String version, String range, byte[] payload)
            throws IOException {
        String path = "META-INF/jarjar/" + artifact + "-" + version + ".jar";
        String metadata = """
                {"jars":[{"identifier":{"group":"example","artifact":"%s"},
                "version":{"range":"%s","artifactVersion":"%s"},"path":"%s","isObfuscated":false}]}
                """.formatted(artifact, range, version, path);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/jarjar/metadata.json"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(path));
            zip.write(payload);
            zip.closeEntry();
        }
        return new Archive(owner, out.toByteArray());
    }

    static void verifyNegotiation(Function<List<Archive>, List<Archive>> selector) throws IOException {
        var runtime = embed("runtime", "small-library", "1.0", "[1,2)", new byte[0]);
        var mod = embed("mod", "compixel-runtime", "1.3", "[1.3,2)", runtime.bytes());
        var newer = embed("other-mod", "small-library", "1.1", "[1.1,2)", new byte[0]);
        var selected = selector.apply(List.of(mod, newer));
        assertEquals(2, selected.size());
        assertTrue(selected.stream().anyMatch(a -> a.id().endsWith("small-library-1.1.jar")));
        assertFalse(selected.stream().anyMatch(a -> a.id().endsWith("small-library-1.0.jar")));
        var incompatible = embed("incompatible-mod", "small-library", "2.0", "[2,3)", new byte[0]);
        assertThrows(IllegalStateException.class, () -> selector.apply(List.of(mod, incompatible)));
    }

    @Test
    void neoforgeNegotiatesThroughTwoLevelsAndRejectsDisjointRanges() throws IOException {
        verifyNegotiation(JarInJarNegotiationTest::neo);
    }

    @Test
    void forgeNegotiatesThroughTwoLevelsAndRejectsDisjointRanges() throws IOException {
        verifyNegotiation(JarInJarNegotiationTest::forge);
    }

    @Test
    @EnabledIfSystemProperty(named = "compixel.runtimeArchive", matches = ".+")
    void bothSelectorsReadEveryLibraryFromTheActualRuntime() throws IOException {
        var bytes = Files.readAllBytes(Path.of(System.getProperty("compixel.runtimeArchive")));
        var mod = embed("compixel", "compixel-runtime", "1.3", "[1.3,2)", bytes);
        for (var selector : List.<Function<List<Archive>, List<Archive>>>of(
                JarInJarNegotiationTest::neo, JarInJarNegotiationTest::forge)) {
            var selected = selector.apply(List.of(mod));
            assertEquals(14, selected.size()); // Runtime plus thirteen independent libraries.
            assertTrue(selected.stream().anyMatch(a -> a.id().contains("jspecify")));
            assertFalse(selected.stream().anyMatch(a -> a.id().contains("kotlin-stdlib")));
            assertFalse(selected.stream().anyMatch(a -> a.id().contains("org.jetbrains.annotations")));
            var paths = new ArrayList<Path>();
            for (var archive : selected) {
                var path = temporary.resolve(archive.id().substring(archive.id().lastIndexOf('/') + 1));
                Files.write(path, archive.bytes());
                paths.add(path);
            }
            var finder = ModuleFinder.of(paths.toArray(Path[]::new));
            assertTrue(
                    finder.find("kotlinx.atomicfu").orElseThrow().descriptor().isAutomatic());
            var swing = finder.find("kotlinx.coroutines.swing").orElseThrow().descriptor();
            assertTrue(swing.isAutomatic());
            assertTrue(swing.provides().stream()
                    .anyMatch(p -> p.service().equals("kotlinx.coroutines.internal.MainDispatcherFactory")));
            assertTrue(finder.find("kotlin.stdlib").isEmpty());
            var roots = finder.findAll().stream()
                    .map(m -> m.descriptor().name())
                    .collect(java.util.stream.Collectors.toSet());
            assertDoesNotThrow(
                    () -> ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.ofSystem(), roots));
        }
    }
}
