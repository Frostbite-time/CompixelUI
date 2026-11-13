package dev.compixel.ui.resources

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.security.MessageDigest
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

private object FontResource : ResourceType<FontFamily> {
    override fun decode(path: String, bytes: ByteArray): FontFamily {
        // Font's data supplier is lazy. Validate here so broken resources use the caller's fallback.
        Data.makeFromBytes(bytes).use { data ->
            checkNotNull(FontMgr.default.makeFromData(data)) { "Invalid font resource: $path" }.close()
        }
        // Compose caches typefaces by Font identity. A changed file needs a new identity after a reload.
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return FontFamily(Font("$path#$hash", { bytes }))
    }
}

/**
 * A TTF or OTF file from the host's resources, as a standard Compose [FontFamily]. Use a complete path such as
 * `example:font/body.ttf`. Resource packs can replace the file, and reloads update existing text in place. Missing or
 * invalid files, and hosts without resources, use [fallback]. Minecraft bitmap/provider JSON is not interpreted.
 */
@Composable
fun fontResource(path: String, fallback: FontFamily = FontFamily.Default): FontFamily =
    LocalResources.current?.load(path, FontResource)?.getOrDefault(fallback) ?: fallback
