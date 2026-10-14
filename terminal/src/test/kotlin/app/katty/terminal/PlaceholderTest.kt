package app.katty.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholderTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    // --- The runs, which are pure ---

    private fun cell(placeholder: TerminalPlaceholder?) =
        TerminalCell("", null, null, TerminalStyle.NONE, TerminalCellWidth.NARROW, placeholder = placeholder)

    private fun row(vararg cells: TerminalPlaceholder?) =
        TerminalRow(TerminalRowId(0, 0), cells.map { cell(it) }, hasPlaceholder = cells.any { it != null })

    private fun ph(low: Int = 7, placement: Int = 0, row: Int? = null, col: Int? = null, high: Int? = null) =
        TerminalPlaceholder(low, placement, row, col, high)

    @Test
    fun aCellThatLeavesOutItsRowAndColumnContinuesTheRunBeforeIt() {
        val runs = placeholderRuns(listOf(row(ph(row = 1, col = 0), ph(), ph(), ph())))

        val run = runs.single()
        assertEquals(0, run.startColumn)
        assertEquals(4, run.width)
        assertEquals(1, run.imageRow)
        assertEquals(0, run.col)
    }

    @Test
    fun aCellThatDoesNotFitStartsARun() {
        // The third cell skips a column, the fifth is another image.
        val runs = placeholderRuns(listOf(row(ph(row = 0, col = 0), ph(), ph(col = 3), ph(), ph(low = 8))))

        assertEquals(listOf(0 to 2, 2 to 2, 4 to 1), runs.map { it.startColumn to it.width })
        assertEquals(listOf(0, 3, 0), runs.map { it.col })
        assertEquals(listOf(7, 7, 8), runs.map { it.imageId })
    }

    @Test
    fun aCellThatIsNotAPlaceholderEndsTheRun() {
        val runs = placeholderRuns(listOf(row(ph(row = 0, col = 0), null, ph(row = 0, col = 2))))

        assertEquals(listOf(0 to 1, 2 to 1), runs.map { it.startColumn to it.width })
    }

    @Test
    fun theHighByteIsPartOfTheImageIdAndAnotherPlacementIsAnotherRun() {
        val runs = placeholderRuns(listOf(row(ph(row = 0, col = 0, high = 1), ph(placement = 3), ph(placement = 3))))

        assertEquals(listOf(7 or (1 shl 24), 7), runs.map { it.imageId })
        assertEquals(listOf(1, 2), runs.map { it.width })
    }

    @Test
    fun anImageThatFillsTheGridIsCutInRows() {
        val place = placeholderPlacement(PlaceholderRun(1, 0, 4, 7, 0, 0, 1), 32, 32, 4, 2, 8, 16)!!

        assertEquals(0.0, place.offsetYPx)
        assertEquals(32.0, place.widthPx)
        assertEquals(16.0, place.heightPx)
        assertEquals(listOf(0, 16, 32, 16), listOf(place.sourceX, place.sourceY, place.sourceWidth, place.sourceHeight))
    }

    @Test
    fun anImageThatIsWiderThanTheGridIsCenteredAndItsMarginsAreNotDrawn() {
        // 32 by 16 on 4 by 2 cells of 8 by 16: it takes the middle 16 pixels of the 32 of the grid's height.
        val top = placeholderPlacement(PlaceholderRun(0, 0, 4, 7, 0, 0, 0), 32, 16, 4, 2, 8, 16)!!
        val bottom = placeholderPlacement(PlaceholderRun(1, 0, 4, 7, 0, 0, 1), 32, 16, 4, 2, 8, 16)!!

        assertEquals(listOf(8.0, 8.0), listOf(top.offsetYPx, top.heightPx))
        assertEquals(listOf(0, 8), listOf(top.sourceY, top.sourceHeight))
        assertEquals(listOf(0.0, 8.0), listOf(bottom.offsetYPx, bottom.heightPx))
        assertEquals(listOf(8, 8), listOf(bottom.sourceY, bottom.sourceHeight))
    }

    @Test
    fun aRunThatIsOnlyMarginShowsNothing() {
        // 32 by 4 on 4 by 3 cells: the image is a strip in the middle row, the rows around it are margin.
        val place = placeholderPlacement(PlaceholderRun(0, 0, 4, 7, 0, 0, 0), 32, 4, 4, 3, 8, 16)
        assertNull(place)
    }

    // --- In a session ---

    private val esc = "\u001b"

    // The marks that say a row and a column.
    private val diacritics = listOf(0x0305, 0x030D, 0x030E, 0x0310)

    private fun placeholder(row: Int?, col: Int?): String = buildString {
        appendCodePoint(0x10EEEE)
        if (row != null) {
            appendCodePoint(diacritics[row])
            if (col != null) appendCodePoint(diacritics[col])
        }
    }

    // The transmission of an RGBA image of [width] by [height] pixels as a virtual placement of [columns] by [rows] cells.
    private fun virtualImage(id: Int, width: Int, height: Int, columns: Int, rows: Int): String {
        val pixels = ByteArray(width * height * 4) { if (it % 4 == 3) 0xFF.toByte() else (it % 251).toByte() }
        val base64 = Base64.getEncoder().encodeToString(pixels)
        // One chunk is enough for the sizes that the tests use (4 chunks at most, which libghostty reads as one).
        val chunks = base64.chunked(4096)
        return buildString {
            chunks.forEachIndexed { index, chunk ->
                val more = if (index == chunks.lastIndex) 0 else 1
                append("${esc}_G")
                if (index == 0) append("a=T,U=1,i=$id,c=$columns,r=$rows,f=32,s=$width,v=$height,q=2,") else append("q=2,")
                append("m=$more;$chunk${esc}\\")
            }
        }
    }

    // A 4 by 2 block of the image [id], whose cells say everything or only what the first one says.
    private fun block(id: Int, explicit: Boolean): String = buildString {
        append("${esc}[38;5;${id}m")
        for (r in 0 until 2) {
            for (c in 0 until 4) append(if (explicit || c == 0) placeholder(r, c) else placeholder(null, null))
            if (r == 0) append("\r\n")
        }
        append("${esc}[0m")
    }

    private fun start(output: String, rows: Int = 6): TerminalSession {
        val file = File.createTempFile("katty-placeholders", ".txt").also {
            it.deleteOnExit()
            it.writeBytes(output.toByteArray())
        }
        return TerminalSession.start(80, rows, command = listOf("/bin/sh", "-c", "cat ${file.path}; sleep 30"), shellIntegration = false)
            .also { sessions += it }
    }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val last = frames.value
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one had ${last.images.size} images:\n" +
                last.rows.joinToString("\n") { r -> r.cells.joinToString("") { it.text.ifEmpty { "·" } } }, e)
        }
    }

    @Test
    fun aBlockOfPlaceholdersShowsTheImageUnderTheTextOneRunForEachRow() {
        val session = start(virtualImage(7, 32, 32, 4, 2) + block(7, explicit = true))

        val frame = session.awaitFrame { it.images.size == 2 }

        assertEquals(listOf(TerminalImageLayer.BELOW_TEXT), frame.images.map { it.layer }.distinct())
        val (first, second) = frame.images.sortedBy { it.top }
        assertEquals(listOf(0f, 0f, 4f, 1f), listOf(first.left, first.top, first.width, first.height))
        assertEquals(listOf(0, 0, 32, 16), listOf(first.sourceX, first.sourceY, first.sourceWidth, first.sourceHeight))
        assertEquals(listOf(0f, 1f, 4f, 1f), listOf(second.left, second.top, second.width, second.height))
        assertEquals(listOf(0, 16, 32, 16), listOf(second.sourceX, second.sourceY, second.sourceWidth, second.sourceHeight))
        assertTrue(first.image === second.image, "Both show the same image")
    }

    @Test
    fun theCellsAreBlankInTheFrame() {
        val session = start(virtualImage(7, 32, 32, 4, 2) + block(7, explicit = true))

        val frame = session.awaitFrame { it.images.size == 2 }

        for (y in 0..1) {
            assertTrue(frame.rows[y].hasPlaceholder)
            for (x in 0 until 4) {
                val cell = frame.rows[y].cells[x]
                assertEquals("", cell.text, "Cell $x,$y")
                assertNotNull(cell.placeholder)
            }
        }
    }

    @Test
    fun cellsThatLeaveOutTheirRowAndColumnGiveTheSameImages() {
        val session = start(virtualImage(7, 32, 32, 4, 2) + block(7, explicit = false))

        val frame = session.awaitFrame { it.images.size == 2 }

        val (first, second) = frame.images.sortedBy { it.top }
        assertEquals(listOf(0f, 4f, 0, 32), listOf(first.left, first.width, first.sourceY, first.sourceWidth))
        // The second row's first cell says its row, and the rest continue it.
        assertEquals(listOf(0f, 4f, 16, 32), listOf(second.left, second.width, second.sourceY, second.sourceWidth))
    }

    @Test
    fun aWideImageIsCenteredInItsGrid() {
        val session = start(virtualImage(7, 32, 16, 4, 2) + block(7, explicit = true))

        val frame = session.awaitFrame { it.images.size == 2 }

        val (first, second) = frame.images.sortedBy { it.top }
        // Half of each row is image: the top one is its bottom half, the other its top half.
        assertEquals(listOf(0.5f, 0.5f), listOf(first.top, first.height))
        assertEquals(listOf(1f, 0.5f), listOf(second.top, second.height))
        assertEquals(listOf(0, 8), listOf(first.sourceY, first.sourceHeight))
        assertEquals(listOf(8, 8), listOf(second.sourceY, second.sourceHeight))
    }

    @Test
    fun anOverwrittenCellSplitsTheRun() {
        // Cell 1 of the first row is overwritten by an x, and the cells after it say their column.
        val session = start(virtualImage(7, 32, 32, 4, 2) + "${esc}[38;5;7m" +
            placeholder(0, 0) + "x" + placeholder(0, 2) + placeholder(0, 3) + "${esc}[0m")

        val frame = session.awaitFrame { it.images.size == 2 }

        val (first, second) = frame.images.sortedBy { it.left }
        assertEquals(listOf(0f, 1f, 0), listOf(first.left, first.width, first.sourceX))
        assertEquals(listOf(2f, 2f, 16), listOf(second.left, second.width, second.sourceX))
        assertEquals("x", frame.rows[0].cells[1].text)
    }

    @Test
    fun twoImagesAreTwoSetsOfRuns() {
        val session = start(
            virtualImage(7, 32, 32, 4, 2) + virtualImage(9, 16, 32, 2, 2) +
                "${esc}[38;5;7m" + placeholder(0, 0) + placeholder(null, null) + "${esc}[38;5;9m" + placeholder(0, 0) + placeholder(null, null) + "${esc}[0m",
        )

        val frame = session.awaitFrame { it.images.size == 2 }

        val (first, second) = frame.images.sortedBy { it.left }
        assertEquals(listOf(0f, 2f), listOf(first.left, first.width))
        assertEquals(listOf(2f, 2f), listOf(second.left, second.width))
        assertEquals(32, first.image.width)
        assertEquals(16, second.image.width)
    }

    @Test
    fun placeholdersMoveUpWithTheTextWhenItScrolls() {
        val session = start(virtualImage(7, 32, 32, 4, 2) + block(7, explicit = true) + "\r\n\r\n\r\n", rows = 4)

        val frame = session.awaitFrame { it.images.size == 1 }

        // The cursor is on row 1 of 4 after the block, and 3 line feeds bring it to row 4: one scroll, and the first row of the block went to the history.
        val image = frame.images.single()
        assertEquals(16, image.sourceY, "It is the second row of the image that is left")
        assertEquals(0f, image.top, "What was the second row of the block is the first row now")
    }

    @Test
    fun placeholdersOfAnImageThatWasNotTransmittedShowNothing() {
        val session = start("${esc}[38;5;5m" + placeholder(0, 0) + placeholder(null, null) + "${esc}[0mdone")

        val frame = session.awaitFrame { f -> f.rows[0].cells.joinToString("") { it.text }.contains("done") }

        assertTrue(frame.images.isEmpty())
        assertTrue(frame.rows[0].hasPlaceholder)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
