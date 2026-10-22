package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhosttyPasteTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 4)

    @AfterTest
    fun close() {
        terminal.close()
    }

    private fun paste(text: String) = terminal.encodePaste(text).toString(Charsets.UTF_8)

    @Test
    fun aPasteIsTheTextWithItsNewlinesAsCarriageReturnsWhenTheProgramDidNotAskForBracketedPaste() {
        assertEquals("one\rtwo\rthree", paste("one\ntwo\nthree"))
    }

    @Test
    fun aPasteIsWrappedInTheMarkersWhenTheProgramAskedForThem() {
        terminal.vtWrite("\u001b[?2004h")

        // The newlines are the text's own, which the program knows to be a paste.
        assertEquals("\u001b[200~one\ntwo\u001b[201~", paste("one\ntwo"))
    }

    @Test
    fun followsTheModeTheProgramSetLater() {
        terminal.vtWrite("\u001b[?2004h")
        assertEquals("\u001b[200~a\u001b[201~", paste("a"))

        terminal.vtWrite("\u001b[?2004l")
        assertEquals("a", paste("a"))
    }

    @Test
    fun theBytesThatCouldBeTakenForCommandsBecomeSpaces() {
        assertEquals("a b c", paste("a\u001bb\u0000c"))
    }

    @Test
    fun theEndMarkerOfBracketedPasteIsNotLetThrough() {
        terminal.vtWrite("\u001b[?2004h")

        // The escape of a marker in the text is a space: it cannot end the paste.
        assertEquals("\u001b[200~x [201~y\u001b[201~", paste("x\u001b[201~y"))
    }

    @Test
    fun aCarriageReturnIsKeptAndIsUnsafeLikeANewline() {
        // It is what Enter sends: the program runs what comes before it.
        assertEquals("echo hi\rrm x\r", paste("echo hi\rrm x\r"))
        assertTrue(terminal.isPasteUnsafe("echo hi\rrm x"))
        assertTrue(terminal.isPasteUnsafe("echo hi\nrm x"))
        assertFalse(terminal.isPasteUnsafe("echo hi"))
    }

    @Test
    fun inABracketedPasteOnlyTheEndMarkerIsUnsafe() {
        terminal.vtWrite("\u001b[?2004h")

        assertFalse(terminal.isPasteUnsafe("echo hi\rrm x\nls"))
        assertTrue(terminal.isPasteUnsafe("x\u001b[201~\rrm x"))
    }

    @Test
    fun textThatIsNotAsciiIsKept() {
        assertEquals("héllo 界 😀", paste("héllo 界 😀"))
    }

    @Test
    fun aLongPasteIsNotCutShort() {
        val text = "0123456789".repeat(10_000)

        assertEquals(text, paste(text))
    }

    @Test
    fun nothingIsNothing() {
        assertContentEquals(ByteArray(0), terminal.encodePaste(""))
    }
}
