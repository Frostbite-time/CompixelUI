package dev.compixel.bridge

import androidx.compose.ui.input.key.Key
import dev.compixel.platform.UiKey
import kotlin.test.*
import org.junit.jupiter.api.Test

class UiKeyTest {
    @Test
    fun `every key reaches Compose as its own key`() {
        val keys = UiKey.entries.filter { it != UiKey.UNKNOWN }.associateWith { it.composeKey() }
        assertFalse(Key.Unknown in keys.values, "Unmapped: ${keys.filterValues { it == Key.Unknown }.keys}")
        assertEquals(keys.size, keys.values.toSet().size, "Two keys share a Compose key")
        assertEquals(Key.Unknown, UiKey.UNKNOWN.composeKey())
    }

    @Test
    fun `layout characters name letters and punctuation only`() {
        val layoutKeys = UiKey.entries.filter { it.followsLayout }
        assertEquals(26 + 11, layoutKeys.size)
        for (character in "abcdefghijklmnopqrstuvwxyz-=[]\\;'`,./") {
            val key = assertNotNull(UiKey.layoutKey(character.code), "No key types '$character'")
            assertTrue(key.followsLayout)
        }
        assertEquals(
            layoutKeys.toSet(),
            "abcdefghijklmnopqrstuvwxyz-=[]\\;'`,./".map { UiKey.layoutKey(it.code) }.toSet(),
        )
        assertEquals(UiKey.A, UiKey.layoutKey('A'.code))
        assertEquals(UiKey.SLASH, UiKey.layoutKey('/'.code))
        // Digits keep their position, and non-Latin letters fall back to the key's US position.
        for (character in listOf('1'.code, 'ф'.code, 'é'.code, '&'.code, ' '.code, 0x1F600)) assertNull(
            UiKey.layoutKey(character)
        )
    }
}
