package app.katty.terminal

import app.katty.ghostty.GhosttyKey
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalKeyTest {
    @Test
    fun hasTheSameEntriesAsGhosttyKey() {
        // Run ghostty-vt/jextract.sh if this fails: it generates both enums from the same list.
        assertEquals(GhosttyKey.entries.map { it.name }, TerminalKey.entries.map { it.name })
    }
}
