package dev.compixel.platform.internal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.function.Function
import kotlin.io.path.listDirectoryEntries
import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class SkikoNativesTest {
    @TempDir lateinit var cache: Path

    private val icu = Random(1).nextBytes(4096)
    private val prefix = Random(2).nextBytes(1000)
    private val suffix = Random(3).nextBytes(500)
    // Other libraries of the same architecture, before and after the one under test in their shared stream.
    private val before = Random(4).nextBytes(300)
    private val after = Random(5).nextBytes(200)

    @Test
    fun `restores a library from the middle of its stream with its ICU data inserted`() {
        val library = prefix + icu + suffix
        val resources = pack("linux-x64", "libskiko-linux-x64.so", prefix + suffix, sha256(library), "1000")
        SkikoNatives.install(resources, "linux-x64", cache)
        val directory = cache.resolve("skiko-linux-x64-${sha256(library)}")
        assertContentEquals(library, Files.readAllBytes(directory.resolve("libskiko-linux-x64.so")))
        assertEquals(listOf("libskiko-linux-x64.so"), directory.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `writes the ICU data beside a Windows library`() {
        val library = prefix + suffix
        val resources = pack("windows-x64", "skiko-windows-x64.dll", library, sha256(library), "icudtl.dat")
        SkikoNatives.install(resources, "windows-x64", cache)
        val directory = cache.resolve("skiko-windows-x64-${sha256(library)}")
        assertContentEquals(library, Files.readAllBytes(directory.resolve("skiko-windows-x64.dll")))
        assertContentEquals(icu, Files.readAllBytes(directory.resolve("icudtl.dat")))
    }

    @Test
    fun `keeps a library Skiko already unpacked`() {
        val library = prefix + icu + suffix
        val hash = sha256(library)
        val existing = cache.resolve("skiko-linux-x64-$hash/libskiko-linux-x64.so")
        Files.createDirectories(existing.parent)
        Files.write(existing, byteArrayOf(1, 2, 3))
        SkikoNatives.install(
            pack("linux-x64", "libskiko-linux-x64.so", prefix + suffix, hash, "1000"),
            "linux-x64",
            cache,
        )
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(existing))
    }

    @Test
    fun `rejects a library that does not match its checksum`() {
        val hash = sha256(byteArrayOf(0))
        val resources = pack("linux-x64", "libskiko-linux-x64.so", prefix + suffix, hash, "1000")
        assertFailsWith<IOException> { SkikoNatives.install(resources, "linux-x64", cache) }
        assertEquals(emptyList(), cache.resolve("skiko-linux-x64-$hash").listDirectoryEntries())
    }

    @Test
    fun `leaves a runtime without packed libraries to Skiko`() {
        SkikoNatives.install({ null }, "linux-x64", cache)
        assertEquals(emptyList(), cache.listDirectoryEntries())
    }

    private fun pack(host: String, library: String, packed: ByteArray, hash: String, icuPlacement: String) =
        mapOf(
                "natives.properties" to
                    ("icu=icudtl.dat.xz\n$host.library=$library\n$host.sha256=$hash\n$host.icu=$icuPlacement\n" +
                            "$host.stream=x64.xz\n$host.offset=${before.size}\n$host.size=${packed.size}\n")
                        .toByteArray(),
                "icudtl.dat.xz" to xz(icu),
                "x64.xz" to xz(before + packed + after),
            )
            .mapKeys { SkikoNatives.DIRECTORY + it.key }
            .let { files -> Function<String, InputStream?> { name -> files[name]?.let(::ByteArrayInputStream) } }

    private fun xz(data: ByteArray) =
        ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(data) } }.toByteArray()

    private fun sha256(data: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))
}
