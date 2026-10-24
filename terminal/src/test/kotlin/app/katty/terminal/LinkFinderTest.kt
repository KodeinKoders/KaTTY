package app.katty.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkFinderTest {
    // A row of [cols] cells with [text] at its start; [marked] is the range of cells that a program marked as a link.
    private fun row(text: String, cols: Int = 10, wrapped: Boolean = false, marked: IntRange = IntRange.EMPTY) = TerminalRow(
        TerminalRowId(0, 0),
        List(cols) { c ->
            TerminalCell(text.getOrNull(c)?.toString().orEmpty(), null, null, TerminalStyle.NONE, TerminalCellWidth.NARROW, hyperlink = c in marked)
        },
        wrapped,
    )

    @Test
    fun aWrittenLinkThatWrapsIsFoundWholeFromEitherRow() {
        val rows = listOf(row("see http:/", wrapped = true), row("/a.b/c end"))
        val link = TerminalLink("http://a.b/c", listOf(TerminalLinkSegment(0, 4..9), TerminalLinkSegment(1, 0..5)))

        assertEquals(link, writtenLink(rows, 5, 0))
        assertEquals(link, writtenLink(rows, 2, 1))
        assertNull(writtenLink(rows, 8, 1), "The word after it is not a link")
    }

    @Test
    fun aWrittenLinkDoesNotGoOnOntoARowThatIsNotItsLine() {
        val rows = listOf(row("http://a.b"), row("/c"))

        assertEquals(TerminalLink("http://a.b", 0, 0..9), writtenLink(rows, 0, 0))
    }

    @Test
    fun aMarkedLinkIsTheCellsOfTheSameAddressOnItsRowsWhateverTheTextSays() {
        val rows = listOf(row("abc  click", wrapped = true, marked = 5..9), row("here  more", marked = 0..3))
        val addresses = { c: Int, r: Int -> if ((r == 0 && c >= 5) || (r == 1 && c <= 3)) "https://example.com" else null }

        val link = TerminalLink("https://example.com", listOf(TerminalLinkSegment(0, 5..9), TerminalLinkSegment(1, 0..3)))
        assertEquals(link, markedLink(rows, 6, 0, addresses))
        assertEquals(link, markedLink(rows, 1, 1, addresses))
        assertNull(markedLink(rows, 0, 0, addresses), "A cell that is not marked")
    }

    @Test
    fun twoMarkedLinksThatTouchAreOneOnlyWhenTheirAddressIsTheSame() {
        val rows = listOf(row("aaaabbbb", cols = 8, marked = 0..7))
        val other = { c: Int, _: Int -> if (c < 4) "https://a" else "https://b" }
        val same = { _: Int, _: Int -> "https://a" }

        assertEquals(TerminalLink("https://a", 0, 0..3), markedLink(rows, 1, 0, other))
        assertEquals(TerminalLink("https://b", 0, 4..7), markedLink(rows, 5, 0, other))
        assertEquals(TerminalLink("https://a", 0, 0..7), markedLink(rows, 5, 0, same))
    }
}
