package app.katty.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CursorBlinkModeFilterTest {
    private val esc = "\u001b"

    // What the terminal gets, for a stream that is read in [pieces]: everything that the filter gave, and what it still holds is lost.
    private fun filtered(vararg pieces: String): String {
        val filter = CursorBlinkModeFilter()
        val out = java.io.ByteArrayOutputStream()
        for (piece in pieces) {
            val bytes = piece.toByteArray(Charsets.ISO_8859_1)
            val length = filter.filter(bytes, bytes.size)
            out.write(filter.output, 0, length)
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun filtered(stream: String) = filtered(*arrayOf(stream))

    private fun readable(text: String) = text.replace(esc, "ESC")

    @Test
    fun takesTheBlinkingOfTheCursorOutOfASetAndAReset() {
        assertEquals("", filtered("$esc[?12l"))
        assertEquals("", filtered("$esc[?12h"))
    }

    @Test
    fun takesOnlyTheTwelveOutOfAListOfModes() {
        assertEquals("$esc[?25h", filtered("$esc[?12;25h"))
        assertEquals("$esc[?25l", filtered("$esc[?25;12l"))
        assertEquals("$esc[?1;2004h", filtered("$esc[?1;12;2004h"))
        assertEquals("$esc[?25h", filtered("$esc[?012;25h"), "Zeros before it are still twelve")
        assertEquals("$esc[?2h", filtered("$esc[?2;12h"), "The last of the list")
    }

    @Test
    fun theCapabilitiesThatEmacsSendsAreTheShowingOfTheCursorOnly() {
        // cnorm and cvvis of xterm-256color.
        assertEquals("$esc[?25h", filtered("$esc[?12l$esc[?25h"))
        assertEquals("$esc[?25h", filtered("$esc[?12;25h"))
    }

    @Test
    fun otherModesAreNotTouched() {
        for (sequence in listOf("?1l", "?25h", "?1049h", "?112h", "?120h", "?1200h", "?2004l", "?1;2h", "?h", "?;h")) {
            val text = "$esc[${sequence}"
            assertEquals(text + "h", filtered(text + "h"), readable(text))
        }
    }

    @Test
    fun aQueryOfTheModeIsLeftForTheTerminalToAnswer() {
        assertEquals("$esc[?12\$p", filtered("$esc[?12\$p"))
    }

    @Test
    fun aSequenceThatIsNotPrivateIsNotTouched() {
        assertEquals("$esc[12l", filtered("$esc[12l"))
        assertEquals("$esc[4h$esc[0m$esc[2 q$esc[H", filtered("$esc[4h$esc[0m$esc[2 q$esc[H"))
    }

    @Test
    fun noParameterAtAllAndAnEmptyOneAreHandled() {
        assertEquals("$esc[?l", filtered("$esc[?l"))
        assertEquals("$esc[?l", filtered("$esc[?12;l"))
        assertEquals("$esc[?;25h", filtered("$esc[?;12;25h"))
    }

    @Test
    fun textAndOtherSequencesAreByteForByte() {
        val text = "hello éè world\r\n$esc]2;title\u0007$esc[38;2;1;2;3m${esc}_Ga=T;AAAA$esc\\$esc(B"
        assertEquals(text, filtered(text))
    }

    @Test
    fun anEscapeThatIsNotASequenceGoesThroughAndWhatFollowsIsStillLookedAt() {
        assertEquals("$esc$esc[?25h", filtered("$esc$esc[?12;25h"))
        assertEquals("$esc[$esc[?25h", filtered("$esc[$esc[?12;25h"))
        assertEquals("a${esc}b", filtered("a${esc}b"))
    }

    @Test
    fun twoSequencesInOnePiece() {
        assertEquals("a$esc[?25hb", filtered("a$esc[?12l$esc[?25hb$esc[?12l"))
    }

    @Test
    fun aSequenceThatIsTooLongIsNotLookedAtWhicheverWayItIsRead() {
        val long = "$esc[?" + (1..20).joinToString(";") { "1$it" } + ";12h"
        assertEquals(long, filtered(long))
        assertEquals(long, filtered(*long.map { it.toString() }.toTypedArray()))
    }

    @Test
    fun aSequenceThatIsAsLongAsCanBeIsLookedAtWhicheverWayItIsRead() {
        // 32 bytes: ESC [ ? , 28 bytes of parameters, h.
        val text = "$esc[?1;2;3;4;5;6;7;8;9;12;13;14;1h"
        assertEquals(32, text.length)
        val expected = "$esc[?1;2;3;4;5;6;7;8;9;13;14;1h"

        assertEquals(expected, filtered(text))
        assertEquals(expected, filtered(*text.map { it.toString() }.toTypedArray()))
    }

    @Test
    fun aPieceWithoutAnEscapeIsGivenBackAsItIs() {
        val filter = CursorBlinkModeFilter()
        val bytes = "plain text\r\n".toByteArray()

        val length = filter.filter(bytes, bytes.size)

        assertSame(bytes, filter.output, "No copy")
        assertEquals(bytes.size, length)
    }

    @Test
    fun aPieceWithSequencesThatAreNotTheModesIsGivenBackAsItIs() {
        val filter = CursorBlinkModeFilter()
        val bytes = "$esc[1mbold$esc[0m".toByteArray()

        val length = filter.filter(bytes, bytes.size)

        assertSame(bytes, filter.output)
        assertEquals(bytes.size, length)
    }

    @Test
    fun onlyTheLengthThatWasReadIsLookedAt() {
        val filter = CursorBlinkModeFilter()
        val bytes = "ab$esc[?12l".toByteArray()

        val length = filter.filter(bytes, 2)

        assertContentEquals("ab".toByteArray(), filter.output.copyOf(length))
    }

    @Test
    fun aSequenceThatIsCutAnywhereIsFilteredAsIfItWasNot() {
        val stream = "start $esc[?12l mid $esc[?12;25h $esc[?1;12;2004h $esc[?1049h $esc[?12\$p end $esc[2 q $esc[?25l"
        val expected = filtered(stream)

        // At every position, at every pair of positions, and a byte at a time.
        for (cut in 0..stream.length) {
            assertEquals(expected, filtered(stream.substring(0, cut), stream.substring(cut)), "Cut at $cut")
        }
        for (first in 0..stream.length step 3) {
            for (second in first..stream.length step 5) {
                assertEquals(expected, filtered(stream.substring(0, first), stream.substring(first, second), stream.substring(second)), "Cut at $first and $second")
            }
        }
        assertEquals(expected, filtered(*stream.map { it.toString() }.toTypedArray()))
    }

    @Test
    fun aHeldBackEscapeThatTurnsOutToBeNothingIsGivenWithTheNextPiece() {
        val filter = CursorBlinkModeFilter()
        val first = "a$esc".toByteArray()
        val second = "b".toByteArray()

        val firstLength = filter.filter(first, first.size)
        val afterFirst = filter.output.copyOf(firstLength).toString(Charsets.ISO_8859_1)
        val secondLength = filter.filter(second, second.size)
        val afterSecond = filter.output.copyOf(secondLength).toString(Charsets.ISO_8859_1)

        assertEquals("a", afterFirst, "The escape may be the start of a sequence")
        assertEquals("${esc}b", afterSecond)
    }
}
