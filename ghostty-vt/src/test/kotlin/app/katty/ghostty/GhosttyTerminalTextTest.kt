package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttyTerminalTextTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5, scrollbackMaxBytes = 50_000_000)
    private val esc = "\u001b"
    private val bel = "\u0007"

    @AfterTest
    fun close() {
        terminal.close()
    }

    private fun lines(count: Int) = repeat(count) { terminal.vtWrite("line $it\r\n") }

    @Test
    fun readsTheRowsOfTheHistoryWithoutMovingTheViewport() {
        lines(30)
        // 30 lines, and the row of the cursor: 31 rows, of which 5 are the screen.
        assertEquals(31, terminal.totalRows)
        val offset = terminal.scrollbar.offset

        assertEquals("line 0\nline 1", terminal.readRows(0, 1))
        assertEquals("line 10", terminal.readRows(10, 10))
        assertEquals(offset, terminal.scrollbar.offset, "The viewport stayed where it was")
    }

    @Test
    fun readsAcrossTheEdgeBetweenTheHistoryAndTheScreen() {
        lines(30)

        assertEquals("line 24\nline 25\nline 26\nline 27", terminal.readRows(24, 27))
        assertEquals("line 29", terminal.readRows(29, 29))
    }

    @Test
    fun readsTheWholeHistoryAndTheScreen() {
        lines(30)

        assertEquals((0 until 30).joinToString("\n") { "line $it" }, terminal.readRows(0, 29))
        assertEquals((0 until 30).joinToString("\n") { "line $it" }, terminal.allText())
    }

    @Test
    fun anEmptyTerminalHasNoText() {
        assertEquals("", terminal.allText())
        assertEquals("", terminal.readRows(0, 0))
    }

    @Test
    fun aSoftWrappedLineIsJoinedOrNot() {
        // 30 characters in 20 columns: two rows, one line.
        terminal.vtWrite("abcdefghijklmnopqrstuvwxyz0123\r\nnext\r\n")

        assertEquals("abcdefghijklmnopqrstuvwxyz0123\nnext", terminal.readRows(0, 2, unwrap = true))
        assertEquals("abcdefghijklmnopqrst\nuvwxyz0123\nnext", terminal.readRows(0, 2, unwrap = false))
    }

    @Test
    fun theBlanksThatEndALineAreDropped() {
        terminal.vtWrite("ab   \r\n  cd  \r\n")

        assertEquals("ab\n  cd", terminal.readRows(0, 1))
    }

    @Test
    fun wideCharactersAndEmojiAreReadWhole() {
        terminal.vtWrite("日本語 x\r\n😀 ok\r\né f\r\n")

        assertEquals("日本語 x\n😀 ok\né f", terminal.readRows(0, 2))
    }

    @Test
    fun rowsThatAreNotThereAreRefused() {
        lines(3)
        val total = terminal.totalRows

        assertFailsWith<IllegalArgumentException> { terminal.readRows(0, total) }
        assertFailsWith<IllegalArgumentException> { terminal.readRows(-1, 1) }
        assertFailsWith<IllegalArgumentException> { terminal.readRows(2, 1) }
    }

    @Test
    fun theRowsOfTheScreenOfTheAlternateScreenAreReadToo() {
        lines(10)
        terminal.vtWrite("$esc[?1049h${esc}[Halt screen")

        // The alternate screen has no history: its rows are all there is.
        assertEquals(5, terminal.totalRows)
        assertEquals("alt screen", terminal.readRows(0, 0))
    }

    @Test
    fun aTrackedRefFollowsItsRowAsTheTerminalScrolls() {
        lines(3)
        terminal.trackCursor().use { ref ->
            assertTrue(ref.hasValue)

            lines(30)

            assertTrue(ref.hasValue, "Its row scrolled into the history, and is still there")
        }
    }

    @Test
    fun aTrackedRefIsGoneOnceItsRowIsPrunedFromTheHistory() {
        GhosttyTerminal(cols = 20, rows = 5, scrollbackMaxBytes = 1_000_000).use { small ->
            small.trackCursor().use { ref ->
                repeat(200_000) { small.vtWrite("line $it\r\n") }

                assertFalse(ref.hasValue)
            }
        }
    }

    // A shell: the prompt, the command that was typed, the start of its output, the output, its end and the next prompt.
    private fun command(typed: String, output: String): GhosttyTrackedRef {
        terminal.vtWrite("$esc]133;A$bel$ $esc]133;B$bel$typed\r\n$esc]133;C$bel")
        val ref = terminal.trackCursor()
        terminal.vtWrite(output + "$esc]133;D;0$bel")
        return ref
    }

    @Test
    fun givesTheOutputOfACommandThatTheShellMarked() {
        command("first", "one\r\ntwo\r\n").use { first ->
            command("second", "three\r\n").use { second ->
                terminal.vtWrite("$esc]133;A$bel$ ")

                assertEquals("three", terminal.commandOutput(second))
                assertEquals("one\ntwo", terminal.commandOutput(first), "The one before is still there")
            }
        }
    }

    @Test
    fun theOutputOfACommandSurvivesTheScrollingOfItsRows() {
        command("seq", "a\r\nb\r\nc\r\n").use { ref ->
            // Commands that follow, until the first scrolled into the history.
            repeat(30) { command("c$it", "o$it\r\n").close() }

            assertEquals("a\nb\nc", terminal.commandOutput(ref))
        }
    }

    @Test
    fun aPlaceThatIsNotInTheOutputOfACommandIsNotOutput() {
        terminal.vtWrite("no marks at all\r\n")
        terminal.trackCursor().use { ref ->
            assertNull(terminal.commandOutput(ref))
        }
    }

    @Test
    fun aCommandWithNoOutputGivesNoText() {
        command("true", "").use { ref ->
            terminal.vtWrite("$esc]133;A$bel$ ")

            val output = terminal.commandOutput(ref)
            assertTrue(output == null || output.isEmpty(), "Nothing, not the prompt: $output")
        }
    }

    @Test
    fun aRefThatIsGoneGivesNoOutput() {
        GhosttyTerminal(cols = 20, rows = 5, scrollbackMaxBytes = 1_000_000).use { small ->
            small.vtWrite("$esc]133;A$bel$ $esc]133;B${bel}x\r\n$esc]133;C$bel")
            small.trackCursor().use { ref ->
                repeat(200_000) { small.vtWrite("line $it\r\n") }

                assertNull(small.commandOutput(ref))
            }
        }
    }

    @Test
    fun theTextOfTheSelectionIsStillTheSelectionsOwn() {
        terminal.vtWrite("hello world\r\n")
        assertNull(terminal.selectedText(), "Nothing is selected")
        assertNotNull(terminal.readRows(0, 0))
    }
}
