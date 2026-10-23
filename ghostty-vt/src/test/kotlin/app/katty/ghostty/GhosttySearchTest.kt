package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttySearchTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5, scrollbackMaxBytes = 50_000_000)
    private val search = GhosttySearch(terminal)
    private val esc = "\u001b"

    @AfterTest
    fun close() {
        search.close()
        terminal.close()
    }

    // Feeds the search until it has been through everything.
    private fun GhosttySearch.settle() {
        repeat(10_000) {
            feed()
            if (tick() == GhosttySearchStatus.COMPLETE) return
        }
        error("The search did not complete")
    }

    private fun find(needle: String): Int {
        search.setNeedle(needle)
        search.settle()
        return search.totalMatches
    }

    // 200 lines, of which a few have the word in them.
    private fun history() {
        repeat(200) {
            terminal.vtWrite(if (it == 2 || it == 50 || it == 120) "row $it needle\r\n" else "row $it\r\n")
        }
    }

    @Test
    fun findsATextOnTheScreen() {
        terminal.vtWrite("alpha needle beta\r\nsecond line\r\n")

        assertEquals(1, find("needle"))
        assertEquals(listOf(GhosttyViewportMatch(6, 0, 11, 0)), search.viewportMatches())
    }

    @Test
    fun findsTextInTheHistory() {
        history()

        assertEquals(3, find("needle"))
        assertEquals(emptyList(), search.viewportMatches(), "None of them is in view: the screen has the last rows")
    }

    @Test
    fun comparesAsciiLettersWithoutTheirCaseAndNothingElse() {
        terminal.vtWrite("Needle NEEDLE needle\r\né É\r\n")

        assertEquals(3, find("nEeDlE"))
        assertEquals(1, find("é"), "Only the é, not the É: the case of other letters is not folded")
    }

    @Test
    fun findsNothingForANeedleThatIsNotThere() {
        history()

        assertEquals(0, find("absent"))
        assertEquals(emptyList(), search.viewportMatches())
        assertNull(search.selectedIndex)
        assertFalse(search.selectNext())
    }

    @Test
    fun selectingMovesTowardOlderMatchesAndWrapsRound() {
        history()
        find("needle")

        assertTrue(search.selectNext())
        assertEquals(0, search.selectedIndex, "The newest first")
        assertTrue(search.selectNext())
        assertEquals(1, search.selectedIndex)
        assertTrue(search.selectNext())
        assertEquals(2, search.selectedIndex, "The oldest")
        assertTrue(search.selectNext())
        assertEquals(0, search.selectedIndex, "Round to the newest")
    }

    @Test
    fun selectingTheOtherWayGoesTowardNewerMatches() {
        history()
        find("needle")

        assertTrue(search.selectPrevious())
        assertEquals(2, search.selectedIndex, "Round to the oldest")
        assertTrue(search.selectPrevious())
        assertEquals(1, search.selectedIndex)
    }

    @Test
    fun selectingScrollsTheViewportToTheMatch() {
        history()
        find("needle")
        val bottom = terminal.scrollbar.offset

        search.selectNext()
        search.selectNext()
        search.selectNext()
        search.feed()

        assertTrue(terminal.scrollbar.offset < bottom, "The oldest match is far up in the history")
        val selected = search.selectedMatch()
        assertTrue(selected != null && selected.startRow in 0..4, "And in view: $selected")
        assertTrue(search.viewportMatches().contains(selected), "It is one of the matches in view")
    }

    @Test
    fun theMatchesInViewFollowTheViewport() {
        history()
        find("needle")

        terminal.scroll(GhosttyScrollViewport.Top)
        search.feed()
        val matches = search.viewportMatches()

        // Row 2 of the history is the third row of the screen.
        assertEquals(listOf(GhosttyViewportMatch(6, 2, 11, 2)), matches)
    }

    @Test
    fun aMatchThatWrapsIsOneThatGoesFromARowToTheNext() {
        // The word starts at the column 17, and the row has 20.
        terminal.vtWrite("abcdefghijklmnopqneedle xyz\r\n")

        assertEquals(1, find("needle"))
        assertEquals(listOf(GhosttyViewportMatch(17, 0, 2, 1)), search.viewportMatches())
    }

    @Test
    fun whatIsWrittenAfterwardsIsFoundAtTheNextFeed() {
        terminal.vtWrite("needle\r\n")
        assertEquals(1, find("needle"))

        terminal.vtWrite("another needle\r\n")
        assertEquals(1, search.totalMatches, "As of the last feed")
        search.settle()

        assertEquals(2, search.totalMatches)
    }

    @Test
    fun theAlternateScreenHasItsOwnResults() {
        terminal.vtWrite("needle one\r\nneedle two\r\n")
        assertEquals(2, find("needle"))

        terminal.vtWrite("$esc[?1049h${esc}[Halt needle")
        search.settle()
        assertEquals(1, search.totalMatches)

        terminal.vtWrite("$esc[?1049l")
        search.settle()
        assertEquals(2, search.totalMatches, "The primary screen's come back")
    }

    @Test
    fun anEmptyNeedleStopsTheSearch() {
        history()
        assertEquals(3, find("needle"))

        search.setNeedle("")
        search.settle()

        assertEquals(0, search.totalMatches)
        assertNull(search.selectedIndex)
    }

    @Test
    fun theSameNeedleKeepsTheResults() {
        history()
        find("needle")
        search.selectNext()

        search.setNeedle("needle")

        assertEquals(3, search.totalMatches)
        assertEquals(0, search.selectedIndex)
    }

    @Test
    fun aSearchCanBeFreedAfterItsTerminal() {
        val other = GhosttyTerminal(cols = 20, rows = 5)
        val orphan = GhosttySearch(other)
        other.close()

        orphan.close()
    }

    @Test
    fun aSearchWhoseTerminalIsClosedSaysSoRatherThanReadingIt() {
        val other = GhosttyTerminal(cols = 20, rows = 5)
        val orphan = GhosttySearch(other)
        other.vtWrite("hello")
        orphan.setNeedle("hello")
        other.close()

        // Its grid is freed memory now: asking for what it found throws, as the terminal's own calls do.
        assertFailsWith<IllegalStateException> { orphan.viewportMatches() }
        assertFailsWith<IllegalStateException> { orphan.selectedMatch() }
        orphan.close()
    }
}
