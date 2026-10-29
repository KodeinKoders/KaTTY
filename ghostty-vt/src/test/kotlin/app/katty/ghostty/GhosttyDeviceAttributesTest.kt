package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GhosttyDeviceAttributesTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5)
    private val replies = StringBuilder()

    init {
        terminal.onWritePty = { replies.append(it.toString(Charsets.UTF_8)) }
    }

    @AfterTest
    fun close() {
        terminal.close()
    }

    private fun ask(query: String): String {
        replies.setLength(0)
        terminal.vtWrite(query)
        return replies.toString().replace("\u001b", "ESC")
    }

    @Test
    fun answersAsAVt220WithColor() {
        assertEquals("ESC[?62;22c", ask("\u001b[c"))
    }

    @Test
    fun answersTheSecondaryAndTheTertiaryQueries() {
        assertEquals("ESC[>1;10;0c", ask("\u001b[>c"))
        assertEquals("ESCP!|00000000ESC\\", ask("\u001b[=c"))
    }
}
