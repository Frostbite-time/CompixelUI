package dev.compixel.gradle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipFile;
import org.tukaani.xz.ARM64Options;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.X86Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

/**
 * Packs the Skiko native libraries of a runtime bundle: each xz-compressed, with the ICU data that Skia embeds in
 * its Linux and macOS libraries stored once. {@code dev.compixel.platform.SkikoNatives} restores the current
 * platform's library at run time, byte for byte.
 *
 * <p>{@code META-INF/compixel-natives/natives.properties} names, for each host such as {@code linux-x64}, the
 * library, its SHA-256 and where its ICU data goes: a byte offset to insert it at, or the name of the file Skiko
 * keeps beside the library on Windows. The packed library is {@code <library>.xz}; the ICU data is {@code
 * icudtl.dat.xz}.
 */
public final class SkikoNativesPack {
    public static final String DIRECTORY = "META-INF/compixel-natives";
    private static final String MANIFEST = "natives.properties";
    private static final String ICU = "icudtl.dat";

    private SkikoNativesPack() {}

    /**
     * Packs the libraries of {@code hosts} from Skiko runtime JARs into {@code output}. Some JARs carry the libraries
     * of other hosts too, and not always the same build; like the bundle's merge, the first JAR in {@code runtimes}
     * with a library supplies it and its checksum.
     */
    public static void pack(List<File> runtimes, List<String> hosts, File output) throws IOException {
        var libraries = new TreeMap<String, Library>();
        byte[] icu = null;
        for (var runtime : runtimes) {
            try (var zip = new ZipFile(runtime)) {
                for (var host : hosts) {
                    var name = libraryName(host);
                    if (libraries.containsKey(host) || zip.getEntry(name) == null) continue;
                    var library = read(zip, name);
                    var hash = new String(read(zip, name + ".sha256"), StandardCharsets.US_ASCII).trim();
                    if (!sha256(library).equals(hash)) throw new IOException(name + " does not match its checksum");
                    libraries.put(host, new Library(name, hash, library));
                }
                if (zip.getEntry(ICU) != null) {
                    var data = read(zip, ICU);
                    if (icu != null && !Arrays.equals(icu, data))
                        throw new IOException("Skiko runtimes carry different ICU data");
                    icu = data;
                }
            }
        }
        if (!libraries.keySet().containsAll(hosts))
            throw new IOException("Skiko libraries for " + libraries.keySet() + ", expected " + hosts);
        if (icu == null) throw new IOException("No Skiko runtime carries " + ICU);

        var directory = new File(output, DIRECTORY);
        deleteRecursively(output);
        Files.createDirectories(directory.toPath());
        var manifest = new ArrayList<String>();
        manifest.add("icu=" + ICU + ".xz");
        var jobs = new ArrayList<Callable<Void>>();
        var shared = icu;
        jobs.add(() -> compress(shared, new FilterOptions[] {new LZMA2Options(6)}, new File(directory, ICU + ".xz")));
        for (var entry : libraries.entrySet()) {
            var host = entry.getKey();
            var library = entry.getValue();
            String placement;
            byte[] packed;
            // Skiko keeps the ICU data beside its Windows libraries; Skia embeds it in the others.
            if (host.startsWith("windows-")) {
                placement = ICU;
                packed = library.bytes;
            } else {
                var offsets = occurrences(library.bytes, icu);
                if (offsets.size() != 1)
                    throw new IOException(
                            "Expected the ICU data once in " + library.name + ", found " + offsets.size());
                int offset = offsets.get(0);
                placement = Integer.toString(offset);
                packed = new byte[library.bytes.length - icu.length];
                System.arraycopy(library.bytes, 0, packed, 0, offset);
                System.arraycopy(library.bytes, offset + icu.length, packed, offset, packed.length - offset);
            }
            manifest.add(host + ".library=" + library.name);
            manifest.add(host + ".sha256=" + library.hash);
            manifest.add(host + ".icu=" + placement);
            // Branch filters make machine code compress better.
            FilterOptions branches = host.endsWith("-arm64") ? new ARM64Options() : new X86Options();
            var filters = new FilterOptions[] {branches, new LZMA2Options(6)};
            jobs.add(() -> compress(packed, filters, new File(directory, library.name + ".xz")));
        }
        runAll(jobs);
        Files.writeString(
                new File(directory, MANIFEST).toPath(),
                String.join("\n", manifest) + "\n",
                StandardCharsets.ISO_8859_1);
    }

    /**
     * Restores every library in a packed bundle and checks it against the checksum Skiko names its unpacked copy
     * after. Returns the hosts it checked.
     */
    public static List<String> verify(ZipFile bundle) throws IOException {
        var manifest = new Properties();
        try (var in = bundle.getInputStream(entry(bundle, DIRECTORY + "/" + MANIFEST))) {
            manifest.load(in);
        }
        var icu = decompress(bundle, DIRECTORY + "/" + manifest.getProperty("icu"));
        var hosts = new ArrayList<String>();
        for (var key : new TreeMap<>(manifest).keySet()) {
            var name = key.toString();
            if (!name.endsWith(".library")) continue;
            var host = name.substring(0, name.length() - ".library".length());
            var library = manifest.getProperty(name);
            var placement = manifest.getProperty(host + ".icu");
            var restored = decompress(bundle, DIRECTORY + "/" + library + ".xz");
            if (!placement.equals(ICU)) {
                int offset = Integer.parseInt(placement);
                var whole = new byte[restored.length + icu.length];
                System.arraycopy(restored, 0, whole, 0, offset);
                System.arraycopy(icu, 0, whole, offset, icu.length);
                System.arraycopy(restored, offset, whole, offset + icu.length, restored.length - offset);
                restored = whole;
            }
            var hash = sha256(restored);
            var skikoHash = new String(read(bundle, library + ".sha256"), StandardCharsets.US_ASCII).trim();
            if (!hash.equals(manifest.getProperty(host + ".sha256")) || !hash.equals(skikoHash))
                throw new IOException("Restored " + library + " does not match its checksum");
            hosts.add(host);
        }
        return hosts;
    }

    /** The file name Skiko gives the library of a host, as {@code System.mapLibraryName} does there. */
    public static String libraryName(String host) {
        if (host.startsWith("windows-")) return "skiko-" + host + ".dll";
        if (host.startsWith("macos-")) return "libskiko-" + host + ".dylib";
        return "libskiko-" + host + ".so";
    }

    private record Library(String name, String hash, byte[] bytes) {}

    private static Void compress(byte[] data, FilterOptions[] filters, File file) throws IOException {
        try (OutputStream out =
                new XZOutputStream(new BufferedOutputStream(Files.newOutputStream(file.toPath())), filters)) {
            out.write(data);
        }
        return null;
    }

    private static byte[] decompress(ZipFile zip, String name) throws IOException {
        try (InputStream in = new XZInputStream(new BufferedInputStream(zip.getInputStream(entry(zip, name))))) {
            return in.readAllBytes();
        }
    }

    private static void runAll(List<Callable<Void>> jobs) throws IOException {
        var pool = Executors.newFixedThreadPool(
                Math.min(jobs.size(), Runtime.getRuntime().availableProcessors()));
        try {
            var futures = new ArrayList<Future<Void>>();
            for (var job : jobs) futures.add(pool.submit(job));
            for (var future : futures) future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while compressing the Skiko libraries", interrupted);
        } catch (ExecutionException failure) {
            throw new IOException("Cannot compress the Skiko libraries", failure.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> occurrences(byte[] data, byte[] block) {
        var found = new ArrayList<Integer>();
        var first = block[0];
        for (int i = 0; i <= data.length - block.length; i++) {
            if (data[i] == first && Arrays.equals(data, i, i + block.length, block, 0, block.length)) found.add(i);
        }
        return found;
    }

    private static java.util.zip.ZipEntry entry(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing " + name + " in " + zip.getName());
        return entry;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        try (var in = zip.getInputStream(entry(zip, name))) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        var children = file.listFiles();
        if (children != null) for (var child : children) deleteRecursively(child);
        Files.deleteIfExists(file.toPath());
    }
}
