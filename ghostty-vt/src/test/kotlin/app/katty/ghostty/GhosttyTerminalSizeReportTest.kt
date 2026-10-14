package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GhosttyTerminalSizeReportTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 10)
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
    fun answersTheSizeOfACellInPixels() {
        terminal.resize(80, 24, 10, 20)

        assertEquals("ESC[6;20;10t", ask("\u001b[16t"))
    }

    @Test
    fun answersTheSizeOfTheTextAreaInPixelsAndInCells() {
        terminal.resize(80, 24, 10, 20)

        assertEquals("ESC[4;480;800t", ask("\u001b[14t"))
        assertEquals("ESC[8;24;80t", ask("\u001b[18t"))
    }

    @Test
    fun answersWithTheSizeOfTheLastResize() {
        terminal.resize(80, 24, 10, 20)
        terminal.resize(40, 12, 9, 19)

        assertEquals("ESC[6;19;9t", ask("\u001b[16t"))
        assertEquals("ESC[4;228;360t", ask("\u001b[14t"))
    }

    @Test
    fun doesNotAnswerBeforeItKnowsTheSizeOfACell() {
        assertEquals("", ask("\u001b[16t"))
    }

    @Test
    fun reportsItsSizeInBandWhenTheProgramAsksForIt() {
        terminal.resize(80, 24, 10, 20)
        replies.setLength(0)

        terminal.vtWrite("\u001b[?2048h")
        assertEquals("ESC[48;24;80;480;800t", replies.toString().replace("\u001b", "ESC"))

        replies.setLength(0)
        terminal.resize(40, 12, 9, 19)
        assertEquals("ESC[48;12;40;228;360t", replies.toString().replace("\u001b", "ESC"))
    }
}
