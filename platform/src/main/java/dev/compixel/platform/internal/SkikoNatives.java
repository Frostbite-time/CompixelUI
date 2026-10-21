package dev.compixel.platform.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Function;
import org.tukaani.xz.XZInputStream;

/**
 * Unpacks this platform's Skiko native library where Skiko itself would, so that Skiko finds and loads it. Runtime
 * bundles store the libraries xz-compressed and keep the ICU data that Skia embeds in its Linux and macOS libraries
 * only once; the build restores every library from them byte for byte before publishing.
 */
public final class SkikoNatives {
    static final String DIRECTORY = "/META-INF/compixel-natives/";

    private SkikoNatives() {}

    /** Call on the client before any Compose or Skia code runs. */
    public static void install() {
        // Skiko loads from this directory instead of its own cache when it is set.
        if (System.getProperty("skiko.library.path") != null) return;
        var host = hostId();
        // Skiko reports an unsupported system itself.
        if (host == null) return;
        Class<?> runtime;
        try {
            runtime = Class.forName("dev.compixel.runtime.RuntimeInfo", false, SkikoNatives.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return;
        }
        var dataPath = Path.of(System.getProperty("skiko.data.path", System.getProperty("user.home") + "/.skiko/"));
        try {
            install(runtime::getResourceAsStream, host, dataPath);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot unpack the Skiko native library for " + host, failure);
        }
    }

    /** Unpacks the library of {@code host} into Skiko's cache under {@code dataPath} unless it is there. */
    static void install(Function<String, InputStream> resources, String host, Path dataPath) throws IOException {
        var manifest = new Properties();
        try (var in = resources.apply(DIRECTORY + "natives.properties")) {
            // A runtime that ships the libraries uncompressed leaves the unpacking to Skiko.
            if (in == null) return;
            manifest.load(in);
        }
        var library = manifest.getProperty(host + ".library");
        if (library == null) throw new IllegalStateException("The CompixelUI runtime has no Skiko library for " + host);
        var hash = manifest.getProperty(host + ".sha256");
        var icu = manifest.getProperty(host + ".icu");
        // Skiko's own cache layout: a directory named after the library's checksum. On Windows, Skia reads the ICU
        // data from a file beside the library; elsewhere it is part of the library.
        var directory = dataPath.resolve("skiko-" + host + "-" + hash);
        var target = directory.resolve(library);
        var icuFile = icu.chars().allMatch(Character::isDigit) ? null : directory.resolve(icu);
        if (unpacked(target, icuFile)) return;
        Files.createDirectories(directory);
        // Skiko's lock for changes to its cache, shared with other processes.
        try (var channel = FileChannel.open(
                        dataPath.resolve(".skiko.lock"),
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE);
                var lock = channel.lock()) {
            if (unpacked(target, icuFile)) return;
            var icuData = decompress(resources, manifest.getProperty("icu"));
            if (icuFile != null && !Files.exists(icuFile)) write(icuFile, out -> out.write(icuData), null);
            if (!Files.exists(target)) {
                long offset = icuFile == null ? Long.parseLong(icu) : -1;
                write(
                        target,
                        out -> {
                            try (var in = open(resources, library + ".xz")) {
                                if (offset >= 0) {
                                    copy(in, out, offset);
                                    out.write(icuData);
                                }
                                in.transferTo(out);
                            }
                        },
                        hash);
            }
        }
    }

    /** Skiko's name for this platform, such as {@code linux-x64}, or null where Skiko has no library. */
    static String hostId() {
        var name = System.getProperty("os.name");
        String os;
        if (name.equals("Mac OS X")) os = "macos";
        else if (name.startsWith("Win")) os = "windows";
        else if (name.equals("Linux")) os = "linux";
        else return null;
        var arch = switch (System.getProperty("os.arch")) {
            case "x86_64", "amd64" -> "x64";
            case "aarch64" -> "arm64";
            default -> null;
        };
        return arch == null ? null : os + "-" + arch;
    }

    private static boolean unpacked(Path library, Path icuFile) {
        return Files.exists(library) && (icuFile == null || Files.exists(icuFile));
    }

    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    /** Writes a temporary file and moves it into place, so that a reader never sees part of it. */
    private static void write(Path target, Writer writer, String sha256) throws IOException {
        var temporary = Files.createTempFile(target.getParent(), "skiko", "");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)), digest)) {
                writer.write(out);
            }
            if (sha256 != null && !HexFormat.of().formatHex(digest.digest()).equals(sha256))
                throw new IOException("The unpacked " + target.getFileName() + " does not match its checksum");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static InputStream open(Function<String, InputStream> resources, String name) throws IOException {
        var in = resources.apply(DIRECTORY + name);
        if (in == null) throw new IOException("The CompixelUI runtime is missing " + name);
        return new XZInputStream(new BufferedInputStream(in));
    }

    private static byte[] decompress(Function<String, InputStream> resources, String name) throws IOException {
        try (var in = open(resources, name)) {
            return in.readAllBytes();
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        var buffer = new byte[64 * 1024];
        while (length > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (read < 0) throw new EOFException("The packed Skiko library ends early");
            out.write(buffer, 0, read);
            length -= read;
        }
    }
}
