package app.katty.terminal.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class PreeditTest {
    @Test
    fun aDeadKeyIsOneCell() {
        assertEquals(listOf(PreeditCell("'", 1)), preeditCells("'"))
        assertEquals(listOf(PreeditCell("´", 1)), preeditCells("´"))
    }

    @Test
    fun aWideCharacterTakesTwoCells() {
        assertEquals(listOf(PreeditCell("あ", 2), PreeditCell("a", 1), PreeditCell("한", 2)), preeditCells("あa한"))
    }

    @Test
    fun aCombiningMarkIsInTheCellOfTheCharacterBeforeIt() {
        // e and a combining acute accent.
        assertEquals(listOf(PreeditCell("é", 1), PreeditCell("x", 1)), preeditCells("éx"))
    }

    @Test
    fun aCombiningMarkThatFollowsNothingIsACellOfItsOwn() {
        assertEquals(listOf(PreeditCell("́", 1)), preeditCells("́"))
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneIsOneCell() {
        assertEquals(listOf(PreeditCell("😀", 2)), preeditCells("😀"))
        assertEquals(listOf(PreeditCell("𝔸", 1)), preeditCells("𝔸"))
    }

    @Test
    fun startsAtTheCursor() {
        assertEquals(5, preeditStart(cursorCol = 5, preeditCols = 3, cols = 80))
    }

    @Test
    fun isShiftedLeftToFitInTheLine() {
        assertEquals(77, preeditStart(cursorCol = 79, preeditCols = 3, cols = 80))
        assertEquals(78, preeditStart(cursorCol = 78, preeditCols = 2, cols = 80))
    }

    @Test
    fun startsOnTheFirstColumnWhenItIsWiderThanTheLine() {
        assertEquals(0, preeditStart(cursorCol = 3, preeditCols = 100, cols = 80))
    }
}
