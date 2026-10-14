package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhosttyTerminalScrollTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 10)
    private val state = GhosttyRenderState()

    @AfterTest
    fun close() {
        state.close()
        terminal.close()
    }

    private fun writeLines(count: Int, from: Int = 1) {
        for (i in from until from + count) terminal.vtWrite("line $i\r\n")
    }

    private fun firstRow(): String {
        state.update(terminal)
        return state.readRows().first().cells!!.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()
    }

    @Test
    fun theViewportStartsAtTheBottomOfTheHistoryAndTheScreen() {
        writeLines(100)

        val scrollbar = terminal.scrollbar

        assertEquals(10, scrollbar.len)
        assertTrue(scrollbar.total >= 100, "The history and the screen: ${scrollbar.total}")
        assertEquals(scrollbar.total - scrollbar.len, scrollbar.offset, "At the bottom")
    }

    @Test
    fun scrollingByRowsShowsTheHistory() {
        writeLines(100)
        val atBottom = terminal.scrollbar
        assertEquals("line 92", firstRow())

        terminal.scroll(GhosttyScrollViewport.Delta(-5))

        assertEquals(atBottom.offset - 5, terminal.scrollbar.offset)
        assertEquals("line 87", firstRow())

        terminal.scroll(GhosttyScrollViewport.Delta(2))

        assertEquals(atBottom.offset - 3, terminal.scrollbar.offset)
        assertEquals("line 89", firstRow())
    }

    @Test
    fun scrollsToTheTopAndTheBottom() {
        writeLines(100)

        terminal.scroll(GhosttyScrollViewport.Top)
        assertEquals(0, terminal.scrollbar.offset)
        assertEquals("line 1", firstRow())

        terminal.scroll(GhosttyScrollViewport.Bottom)
        val scrollbar = terminal.scrollbar
        assertEquals(scrollbar.total - scrollbar.len, scrollbar.offset)
        assertEquals("line 92", firstRow())
    }

    @Test
    fun scrollsToARowOfTheScrollbar() {
        writeLines(100)

        terminal.scroll(GhosttyScrollViewport.Row(10))

        assertEquals(10, terminal.scrollbar.offset)
        assertEquals("line 11", firstRow())
        // The offset of the scrollbar is the row that is asked for.
        terminal.scroll(GhosttyScrollViewport.Row(terminal.scrollbar.offset))
        assertEquals(10, terminal.scrollbar.offset)
    }

    @Test
    fun theViewportDoesNotGoPastEitherEnd() {
        writeLines(100)
        val bottom = terminal.scrollbar.offset

        terminal.scroll(GhosttyScrollViewport.Delta(1000))
        assertEquals(bottom, terminal.scrollbar.offset)

        terminal.scroll(GhosttyScrollViewport.Delta(-100_000))
        assertEquals(0, terminal.scrollbar.offset)

        terminal.scroll(GhosttyScrollViewport.Row(1_000_000))
        assertEquals(bottom, terminal.scrollbar.offset)
    }

    @Test
    fun whatTheProgramWritesDoesNotMoveAViewportThatWasScrolled() {
        writeLines(100)
        terminal.scroll(GhosttyScrollViewport.Delta(-20))
        val offset = terminal.scrollbar.offset
        val shown = firstRow()

        writeLines(10, from = 101)

        assertEquals(offset, terminal.scrollbar.offset)
        assertEquals(shown, firstRow())
        assertTrue(terminal.scrollbar.total >= 110)
    }

    @Test
    fun aScrolledViewportHasNoCursor() {
        writeLines(100)
        terminal.scroll(GhosttyScrollViewport.Delta(-20))
        state.update(terminal)

        assertEquals(null, state.cursor.x)
    }

    @Test
    fun theAlternateScreenHasNoHistory() {
        writeLines(100)
        assertEquals(GhosttyScreen.PRIMARY, terminal.activeScreen)

        terminal.vtWrite("\u001b[?1049h")
        for (i in 1..50) terminal.vtWrite("alt $i\r\n")

        assertEquals(GhosttyScreen.ALTERNATE, terminal.activeScreen)
        assertEquals(terminal.scrollbar.len, terminal.scrollbar.total)
        terminal.scroll(GhosttyScrollViewport.Delta(-5))
        assertEquals(0, terminal.scrollbar.offset)

        terminal.vtWrite("\u001b[?1049l")
        assertEquals(GhosttyScreen.PRIMARY, terminal.activeScreen)
        assertTrue(terminal.scrollbar.total >= 100, "The history of the main screen is back")
    }

    @Test
    fun alternateScrollIsOnUnlessTheProgramTurnsItOff() {
        // libghostty's default, which is what the wheel follows.
        assertEquals(true, terminal.isModeEnabled(GhosttyMode.ALT_SCROLL))

        terminal.vtWrite("\u001b[?1007l")
        assertEquals(false, terminal.isModeEnabled(GhosttyMode.ALT_SCROLL))

        terminal.vtWrite("\u001b[?1007h")
        assertEquals(true, terminal.isModeEnabled(GhosttyMode.ALT_SCROLL))
    }

    @Test
    fun theHistoryIsLimitedInMemory() {
        GhosttyTerminal(cols = 20, rows = 10, scrollbackMaxBytes = 1_000).use { limited ->
            for (i in 1..30_000) limited.vtWrite("line $i\r\n")

            assertTrue(limited.scrollbar.total < 30_000, "Pruned: ${limited.scrollbar.total} rows")
        }
    }
}
