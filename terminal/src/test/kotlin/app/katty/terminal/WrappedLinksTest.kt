package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Links on a screen of 20 columns, where an address does not fit in a row.
class WrappedLinksTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    // [script] writes what is to be looked at and then "END" on the last row.
    private fun start(script: String, rows: Int = 8): TerminalSession {
        val session = TerminalSession.start(20, rows, command = listOf("/bin/sh", "-c", "$script; printf '\\033[$rows;1HEND'; sleep 30"), shellIntegration = false)
        sessions += session
        runBlocking {
            withTimeout(15_000) { session.frames.first { f -> f.rows[rows - 1].cells.take(3).joinToString("") { it.text } == "END" } }
        }
        return session
    }

    private fun segments(link: TerminalLink?) = link?.segments?.map { it.row to it.columns }

    @Test
    fun aRowIsMarkedAsWrappedWhenItsLineGoesOnInTheNext() {
        val session = start("printf '0123456789012345678901234\\r\\nnext'")

        val rows = session.frames.value.rows
        assertEquals(listOf(true, false, false), rows.take(3).map { it.wrapped })
    }

    @Test
    fun anAddressThatIsWrittenInTheTextAndWrapsIsFoundWholeFromAnyOfItsCells() {
        // "see " is 4 characters, then the 22 of the address: 16 on the first row and 6 on the second.
        val session = start("printf 'see https://kodein.net/abc'")

        val expected = listOf(0 to 4..19, 1 to 0..5)
        for ((column, row) in listOf(10 to 0, 4 to 0, 19 to 0, 0 to 1, 5 to 1)) {
            val link = session.linkAt(column, row)
            assertEquals("https://kodein.net/abc", link?.url, "At $column,$row")
            assertEquals(expected, segments(link), "At $column,$row")
        }
        assertNull(session.linkAt(2, 0), "The word before it")
        assertNull(session.linkAt(6, 1), "After it")
    }

    @Test
    fun theSentenceThatEndsAnAddressOnTheNextRowIsNotPartOfIt() {
        val session = start("printf 'see https://kodein.net/ab. ok'")

        // The address ends at the dot, which is the sixth cell of the second row.
        val link = session.linkAt(10, 0)
        assertEquals("https://kodein.net/ab", link?.url)
        assertEquals(listOf(0 to 4..19, 1 to 0..4), segments(link))
        assertNull(session.linkAt(5, 1), "The dot")
    }

    @Test
    fun aRowThatDoesNotWrapEndsTheAddress() {
        // The address fills the first row exactly and a line feed follows: what is on the next row is another line.
        val session = start("printf 'https://kodein.net/\\r\\nfoo'")

        val link = session.linkAt(3, 0)
        assertEquals("https://kodein.net/", link?.url)
        assertEquals(listOf(0 to 0..18), segments(link))
        assertNull(session.linkAt(1, 1), "Not part of it")
    }

    @Test
    fun anAddressThatTheProgramMarkedAndWrapsIsFoundWhole() {
        // The link is 25 cells: all the first row and 5 cells of the second.
        val session = start("printf '\\033]8;;https://example.com/x\\033\\\\0123456789012345678901234\\033]8;;\\033\\\\ after'")

        for ((column, row) in listOf(0 to 0, 19 to 0, 0 to 1, 4 to 1)) {
            val link = session.linkAt(column, row)
            assertEquals("https://example.com/x", link?.url, "At $column,$row")
            assertEquals(listOf(0 to 0..19, 1 to 0..4), segments(link), "At $column,$row")
        }
        assertNull(session.linkAt(5, 1), "The space after it")
    }

    @Test
    fun aMarkedLinkThatStartsInTheMiddleOfARowAndWraps() {
        val session = start("printf 'ab\\033]8;;https://example.com/y\\033\\\\0123456789012345678901\\033]8;;\\033\\\\'")

        val link = session.linkAt(1, 1)
        assertEquals("https://example.com/y", link?.url)
        // 18 cells on the first row (columns 2 to 19), then 4.
        assertEquals(listOf(0 to 2..19, 1 to 0..3), segments(link))
        assertNull(session.linkAt(0, 0), "ab")
    }

    @Test
    fun aMarkedLinkIsNotJoinedAcrossARowThatDoesNotWrap() {
        val session = start("printf '\\033]8;;https://example.com/z\\033\\\\01234567890123456789\\033]8;;\\033\\\\\\r\\n\\033]8;;https://example.com/z\\033\\\\more\\033]8;;\\033\\\\'")

        assertEquals(listOf(0 to 0..19), segments(session.linkAt(3, 0)))
        assertEquals(listOf(1 to 0..3), segments(session.linkAt(1, 1)))
    }

    @Test
    fun aLinkIsFoundWithinTheRowsThatAreLookedAtEvenWhenTheLineGoesOnForScreens() {
        // The line is 40 rows long: only some of them are read for the address on the first.
        val session = start("printf 'x https://kodein.net/a '; i=0; while [ \$i -lt 38 ]; do printf 'yyyyyyyyyyyyyyyyyyyy'; i=\$((i+1)); done", rows = 40)

        assertEquals("https://kodein.net/a", session.linkAt(5, 0)?.url)
        assertNull(session.linkAt(5, 30), "Nothing there")
    }
}
