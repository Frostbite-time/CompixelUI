package dev.compixel.host

import kotlin.test.*
import org.junit.jupiter.api.Test

class HostInputTest {
    @Test
    fun `supplementary Unicode is committed atomically`() {
        val input = CommittedCharacters()
        assertNull(input.accept('\uD83D'))
        assertEquals("😀", input.accept('\uDE00'))
    }

    @Test
    fun `focus loss discards an incomplete surrogate`() {
        val input = CommittedCharacters()
        input.accept('\uD83D')
        input.reset()
        assertNull(input.accept('\uDE00'))
        assertEquals("a", input.accept('a'))
    }

    @Test
    fun `control characters and lone surrogates are not committed`() {
        val input = CommittedCharacters()
        assertNull(input.accept('\n'))
        assertNull(input.accept('\uDE00'))
        assertEquals("中", input.accept('中'))
    }

    @Test
    fun `clipboard mailbox makes pending writes visible without the system clipboard`() {
        val clipboard = ClipboardMailbox()
        clipboard.refresh("old")
        clipboard.writeText("new")
        clipboard.refresh("stale OS snapshot")
        assertEquals("new", clipboard.readText())
        assertEquals("new", clipboard.takeWrite())
        assertEquals("new", clipboard.readText())
        assertNull(clipboard.takeWrite())
    }

    @Test
    fun `clipboard supports clearing text`() {
        val clipboard = ClipboardMailbox()
        clipboard.refresh("old")
        clipboard.writeText("")
        assertEquals("", clipboard.takeWrite())
        assertEquals("", clipboard.readText())
    }
}
