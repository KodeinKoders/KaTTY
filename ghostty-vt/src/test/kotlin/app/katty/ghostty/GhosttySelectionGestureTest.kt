package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttySelectionGestureTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 4)
    private val gesture = GhosttySelectionGesture(terminal)
    private val state = GhosttyRenderState()

    @AfterTest
    fun close() {
        gesture.close()
        state.close()
        terminal.close()
    }

    // Cells of 10 by 20 pixels.
    private val cellWidth = 10
    private val cellHeight = 20

    private var clock = 0L

    private fun press(column: Int, row: Int, afterMs: Long = 1000, button: Boolean = true): Boolean {
        clock += afterMs * 1_000_000
        return gesture.press(
            column, row, column * cellWidth + 2.0, row * cellHeight + 8.0,
            timeNs = clock, repeatDistancePx = 5.0, repeatIntervalNs = 500_000_000,
        )
    }

    // In the right half of the cell, which is what includes it in the selection that ends there.
    private fun drag(column: Int, row: Int, rectangle: Boolean = false) =
        gesture.drag(
            column, row, column * cellWidth + 7.0, row * cellHeight + 8.0,
            cols = 20, cellWidthPx = cellWidth, screenHeightPx = 4 * cellHeight, rectangle = rectangle,
        )

    private fun rowSelections(): List<IntRange?> {
        state.update(terminal)
        return state.readRows().map { it.selection }
    }

    @Test
    fun aSingleClickSelectsNothingAndADragSelectsWhatItCovers() {
        terminal.vtWrite("hello world\r\nsecond line")

        assertFalse(press(0, 0), "A click is where a drag starts from")
        assertNull(terminal.selectedText())

        assertTrue(drag(4, 0))
        gesture.release(4, 0)

        assertEquals("hello", terminal.selectedText())
        assertEquals(listOf(0..4, null, null, null), rowSelections())
    }

    @Test
    fun aDragOverSeveralLinesSelectsAFlowOfText() {
        terminal.vtWrite("hello world\r\nsecond line")

        press(6, 0)
        drag(5, 1)
        gesture.release(5, 1)

        assertEquals("world\nsecond", terminal.selectedText())
        assertEquals(listOf(6..19, 0..5, null, null), rowSelections())
    }

    @Test
    fun aRectangleSelectsABlockOfCells() {
        terminal.vtWrite("hello world\r\nsecond line")

        press(2, 0)
        drag(4, 1, rectangle = true)
        gesture.release(4, 1)

        assertEquals("llo\ncon", terminal.selectedText())
        assertEquals(listOf(2..4, 2..4, null, null), rowSelections())
    }

    @Test
    fun aDoubleClickSelectsAWord() {
        terminal.vtWrite("hello world")

        assertFalse(press(7, 0))
        gesture.release(7, 0)
        assertTrue(press(7, 0, afterMs = 100), "The second click of two")
        gesture.release(7, 0)

        assertEquals("world", terminal.selectedText())
    }

    @Test
    fun aTripleClickSelectsALine() {
        terminal.vtWrite("hello world\r\nsecond line")

        press(7, 0)
        gesture.release(7, 0)
        press(7, 0, afterMs = 100)
        gesture.release(7, 0)
        assertTrue(press(7, 0, afterMs = 100))
        gesture.release(7, 0)

        assertEquals("hello world", terminal.selectedText())
    }

    @Test
    fun clicksThatAreTooFarApartInTimeAreNotRepeated() {
        terminal.vtWrite("hello world")

        press(7, 0)
        gesture.release(7, 0)

        assertFalse(press(7, 0, afterMs = 900), "A second click after the interval is a first one")
    }

    @Test
    fun theSelectionTextJoinsSoftWrappedLinesAndTrimsTheEndOfLines() {
        // 20 columns: the line wraps without a newline.
        terminal.vtWrite("0123456789012345678901234\r\nend")

        press(0, 0)
        drag(2, 2)
        gesture.release(2, 2)

        assertEquals("0123456789012345678901234\nend", terminal.selectedText())
    }

    @Test
    fun clearingUnselects() {
        terminal.vtWrite("hello world")
        press(0, 0)
        drag(4, 0)

        terminal.clearSelection()

        assertNull(terminal.selectedText())
        assertEquals(listOf(null, null, null, null), rowSelections())
    }

    @Test
    fun theSelectionStaysOnTheCellsWhenTheProgramWritesOverThem() {
        // libghostty does not unselect: a terminal that wants to has to.
        terminal.vtWrite("hello world")
        press(0, 0)
        drag(4, 0)
        assertEquals("hello", terminal.selectedText())

        terminal.vtWrite("\u001b[1;1HHELLO")

        assertEquals("HELLO", terminal.selectedText())
    }

    @Test
    fun aDragPastAnEdgeAsksToScrollThatWay() {
        for (i in 1..20) terminal.vtWrite("line $i\r\n")
        press(2, 1)

        gesture.drag(2, 3, 25.0, 4 * cellHeight + 30.0, cols = 20, cellWidthPx = cellWidth, screenHeightPx = 4 * cellHeight, rectangle = false)
        assertEquals(GhosttyAutoscroll.DOWN, gesture.autoscroll())

        gesture.drag(2, 0, 25.0, -15.0, cols = 20, cellWidthPx = cellWidth, screenHeightPx = 4 * cellHeight, rectangle = false)
        assertEquals(GhosttyAutoscroll.UP, gesture.autoscroll())

        gesture.drag(2, 2, 25.0, 2 * cellHeight + 8.0, cols = 20, cellWidthPx = cellWidth, screenHeightPx = 4 * cellHeight, rectangle = false)
        assertEquals(GhosttyAutoscroll.NONE, gesture.autoscroll())
    }

    @Test
    fun aTickScrollsTheViewportAndExtendsTheSelectionToTheEdge() {
        for (i in 1..20) terminal.vtWrite("line $i\r\n")
        terminal.scroll(GhosttyScrollViewport.Delta(-6))
        press(0, 0)
        gesture.drag(5, 3, 57.0, 4 * cellHeight + 30.0, cols = 20, cellWidthPx = cellWidth, screenHeightPx = 4 * cellHeight, rectangle = false)
        val before = terminal.selectedText()!!.lines().size
        val offset = terminal.scrollbar.offset

        assertTrue(gesture.tick(5, 3, 57.0, 4 * cellHeight + 30.0, cols = 20, cellWidthPx = cellWidth, screenHeightPx = 4 * cellHeight, rectangle = false))

        // The tick moved the viewport itself, and the selection went with it.
        assertEquals(offset + 1, terminal.scrollbar.offset)
        assertTrue(terminal.selectedText()!!.lines().size > before, "The selection follows the scroll")
    }
}
