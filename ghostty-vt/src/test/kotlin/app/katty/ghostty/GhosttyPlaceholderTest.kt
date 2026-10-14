package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttyPlaceholderTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5, imageStorageLimitBytes = 10_000_000)
    private val esc = "\u001b"
    private val placeholder = String(Character.toChars(PLACEHOLDER_CODEPOINT))

    @AfterTest
    fun close() {
        terminal.close()
    }

    // The diacritics that stand for 0, 1, 2 and 3.
    private fun mark(index: Int) = String(Character.toChars(listOf(0x0305, 0x030D, 0x030E, 0x0310)[index]))

    private fun firstRow(): GhosttyRenderRow = GhosttyRenderState().use { state ->
        state.update(terminal)
        state.readRows().first()
    }

    @Test
    fun theTableHasTheTwoHundredAndNinetySevenMarksOfKitty() {
        assertEquals(297, PLACEHOLDER_DIACRITICS)
        assertEquals(0, placeholderDiacriticIndex(0x0305))
        assertEquals(1, placeholderDiacriticIndex(0x030D))
        assertEquals(3, placeholderDiacriticIndex(0x0310))
        assertEquals(296, placeholderDiacriticIndex(0x1D244), "The last one")
        assertNull(placeholderDiacriticIndex(0x0041), "A letter is not one")
        assertNull(placeholderDiacriticIndex(0x0300), "Nor is a mark that is not in the list")
    }

    @Test
    fun aCellHasItsRowItsColumnAndTheIdsOfItsColors() {
        terminal.vtWrite("$esc[38;2;1;2;3m$esc[58;2;0;0;7m$placeholder${mark(0)}${mark(1)}${mark(2)}")

        val cell = firstRow().cells!![0]

        assertEquals(GhosttyPlaceholderCell(0x010203, 7, row = 0, col = 1, high = 2), cell.placeholder)
        assertEquals("", "")
        assertTrue(cell.text.startsWith(placeholder), "The text is what was written")
    }

    @Test
    fun aPaletteColorIsTheIdAsItIsAndNotItsRgb() {
        terminal.vtWrite("$esc[38;5;200m$placeholder${mark(2)}${mark(3)}")

        val cell = firstRow().cells!![0]

        assertEquals(200, cell.placeholder!!.imageIdLow)
        assertEquals(0, cell.placeholder!!.placementId, "No underline color: no placement")
        assertEquals(2, cell.placeholder!!.row)
        assertEquals(3, cell.placeholder!!.col)
        assertNull(cell.placeholder!!.high)
    }

    @Test
    fun aCellWithoutColorsHasTheIdZero() {
        terminal.vtWrite(placeholder)

        assertEquals(GhosttyPlaceholderCell(0, 0, null, null, null), firstRow().cells!![0].placeholder)
    }

    @Test
    fun aMarkThatIsNotOneIsAsIfItWasNotThere() {
        terminal.vtWrite("$esc[38;5;9m$placeholdeŕ${mark(1)}")

        val cell = firstRow().cells!![0]

        assertNull(cell.placeholder!!.row, "U+0301 is not in the list")
        assertEquals(1, cell.placeholder!!.col)
    }

    @Test
    fun aHighByteThatDoesNotFitAByteIsDropped() {
        // The mark at index 300 would be too big: the 297 marks are the most that there are, so the last one (296) is the biggest and fits.
        terminal.vtWrite("$esc[38;5;9m$placeholder${mark(0)}${mark(0)}${String(Character.toChars(0x1D244))}")

        assertNull(firstRow().cells!![0].placeholder!!.high)
    }

    @Test
    fun otherCellsAreNotPlaceholders() {
        terminal.vtWrite("abc ${placeholder}")

        val cells = firstRow().cells!!
        assertNull(cells[0].placeholder)
        assertNotNull(cells[4].placeholder)
    }

    @Test
    fun theRowSaysThatItHasPlaceholders() {
        terminal.vtWrite("no placeholder here\r\n$esc[38;5;9m$placeholder")

        val rows = GhosttyRenderState().use { state ->
            state.update(terminal)
            state.readRows()
        }
        assertFalse(rows[0].hasPlaceholder)
        assertTrue(rows[1].hasPlaceholder)
    }

    @Test
    fun theRowSaysThatItWrapsOntoTheNextOne() {
        terminal.vtWrite("a line that is longer than the twenty columns of the screen\r\nshort\r\n")

        val rows = GhosttyRenderState().use { state ->
            state.update(terminal)
            state.readRows()
        }
        assertTrue(rows[0].wrap, "The first row of a long line goes on")
        assertTrue(rows[1].wrap)
        assertFalse(rows[2].wrap, "The last row of it does not")
        assertFalse(rows[3].wrap)
    }

    @Test
    fun aVirtualPlacementHasTheGridThatTheProgramAskedFor() {
        terminal.resize(20, 5, 10, 20)
        terminal.vtWrite("$esc" + "_Ga=T,U=1,i=7,c=4,r=2,f=32,s=1,v=1,q=2;AAAAAA==$esc\\")

        val placement = terminal.kittyPlacements().single()

        assertTrue(placement.isVirtual)
        assertEquals(7, placement.imageId)
        assertEquals(4, placement.columns)
        assertEquals(2, placement.rows)
    }
}
