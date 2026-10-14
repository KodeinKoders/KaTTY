package app.katty.terminal

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutputTranslatorTest {
    private val esc = "\u001b"

    // What the terminal gets, for a stream that is read in [pieces]: everything that the filter gave, and what it still holds is lost.
    private fun filtered(vararg pieces: String): String {
        val filter = OutputTranslator()
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
        val filter = OutputTranslator()
        val bytes = "plain text\r\n".toByteArray()

        val length = filter.filter(bytes, bytes.size)

        assertSame(bytes, filter.output, "No copy")
        assertEquals(bytes.size, length)
    }

    @Test
    fun aPieceWithSequencesThatAreNotTheModesIsGivenBackAsItIs() {
        val filter = OutputTranslator()
        val bytes = "$esc[1mbold$esc[0m".toByteArray()

        val length = filter.filter(bytes, bytes.size)

        assertSame(bytes, filter.output)
        assertEquals(bytes.size, length)
    }

    @Test
    fun onlyTheLengthThatWasReadIsLookedAt() {
        val filter = OutputTranslator()
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
        val filter = OutputTranslator()
        val first = "a$esc".toByteArray()
        val second = "b".toByteArray()

        val firstLength = filter.filter(first, first.size)
        val afterFirst = filter.output.copyOf(firstLength).toString(Charsets.ISO_8859_1)
        val secondLength = filter.filter(second, second.size)
        val afterSecond = filter.output.copyOf(secondLength).toString(Charsets.ISO_8859_1)

        assertEquals("a", afterFirst, "The escape may be the start of a sequence")
        assertEquals("${esc}b", afterSecond)
    }

    // ---- The cursor that a command leaves, and the strings that pass

    private val bel = "\u0007"

    private fun mark(letter: String) = "$esc]133;$letter$bel"

    // The result is the same whichever way the stream is read.
    private fun assertTheSameWhenCut(stream: String, expected: String = filtered(stream)) {
        for (cut in 0..stream.length) {
            assertEquals(expected, filtered(stream.substring(0, cut), stream.substring(cut)), "Cut at $cut")
        }
        assertEquals(expected, filtered(*stream.map { it.toString() }.toTypedArray()), "A byte at a time")
    }

    @Test
    fun theCursorThatTheShellHadIsGivenBackWhenTheNextPromptStarts() {
        val stream = mark("A") + "$ " + mark("B") + "vim\r\n" + mark("C") + "$esc[5 q$esc[?25l" + "output" + mark("D;0") + mark("A") + "$ "

        val result = filtered(stream)

        // The default style and the cursor shown, before the mark of the next prompt.
        assertEquals(stream.replace(mark("D;0") + mark("A"), mark("D;0") + "$esc[0 q$esc[?25h" + mark("A")), result)
        assertTheSameWhenCut(stream)
    }

    @Test
    fun theShellsOwnStyleIsWhatIsGivenBack() {
        val stream = "$esc[2 q" + mark("A") + "$ " + mark("C") + "$esc[6 q" + mark("A")

        assertEquals("$esc[2 q" + mark("A") + "$ " + mark("C") + "$esc[6 q" + "$esc[2 q" + mark("A"), filtered(stream))
    }

    @Test
    fun nothingIsAddedWhenTheCommandDidNotChangeTheCursor() {
        val stream = mark("A") + "$ " + mark("C") + "output\r\n" + mark("D;0") + mark("A")

        assertEquals(stream, filtered(stream))
    }

    @Test
    fun nothingIsAddedWithoutTheMarksOfTheShell() {
        val stream = "$esc[5 q$esc[?25l text $esc[?25h"

        assertEquals(stream, filtered(stream))
    }

    @Test
    fun aPromptThatNoCommandPrecededChangesNothing() {
        val stream = "$esc[5 q" + mark("A") + "$ " + mark("A")

        assertEquals(stream, filtered(stream))
    }

    @Test
    fun theMarksThatEndWithAStringTerminatorAreSeenToo() {
        val stream = "$esc]133;C$esc\\$esc[5 q$esc]133;A$esc\\"

        assertEquals("$esc]133;C$esc\\$esc[5 q$esc[0 q$esc]133;A$esc\\", filtered(stream))
    }

    @Test
    fun theMarksWithParametersAreSeenToo() {
        val stream = "$esc]133;C;cmdline=ls$bel$esc[3 q$esc]133;A;cl=line;aid=1$bel"

        assertEquals("$esc]133;C;cmdline=ls$bel$esc[3 q$esc[0 q$esc]133;A;cl=line;aid=1$bel", filtered(stream))
    }

    @Test
    fun theVisibilityInAListOfModesIsFollowed() {
        val stream = mark("C") + "$esc[?1;25l" + "$esc[?12;2004h" + mark("A")

        assertEquals(mark("C") + "$esc[?1;25l" + "$esc[?2004h" + "$esc[?25h" + mark("A"), filtered(stream))
    }

    @Test
    fun whatTheCursorIsGivenBackToIsTheSameWhicheverWayTheStreamIsCut() {
        val stream = "$esc[4 q" + mark("A") + mark("C") + "$esc[?12l$esc[1 q$esc[?25l" + "out" + mark("D;1") + mark("A") + "$esc[6 q"

        assertTheSameWhenCut(stream)
    }

    @Test
    fun aDecscusrThatTheTerminalWouldIgnoreDoesNotChangeWhatIsKept() {
        val stream = mark("C") + "$esc[9 q" + mark("A")

        assertEquals(stream, filtered(stream))
    }

    @Test
    fun stringsThatAreNotTranslatedGoByAsTheyAre() {
        val streams = listOf(
            "$esc]52;c;aGVsbG8=$bel",
            "$esc]2;a title$esc\\",
            "$esc]1337;CurrentDir=/tmp$bel",
            "$esc]8;;https://example.com$esc\\link$esc]8;;$esc\\",
            "${esc}_Ga=T,f=32,s=2,v=2;/////////////////////w==$esc\\",
            "${esc}P1000p$esc\\",
            "${esc}P+q544e$esc\\",
            "${esc}]7;file://host/tmp$bel",
            "${esc}^private message$esc\\",
        )
        for (stream in streams) {
            assertEquals(stream, filtered(stream), readable(stream))
            assertTheSameWhenCut(stream)
        }
    }

    @Test
    fun aStringThatIsCancelledEndsAndWhatFollowsIsRead() {
        val stream = "$esc]2;a title\u0018$esc[?12l after"

        assertEquals("$esc]2;a title\u0018 after", filtered(stream))
    }

    @Test
    fun anEscapeInsideAStringThatIsNotItsEndStartsASequence() {
        // The string ended there, and what follows is a sequence of its own.
        val stream = "$esc]2;title$esc[?12;25h"

        assertEquals("$esc]2;title$esc[?25h", filtered(stream))
    }

    @Test
    fun aBigStringGoesByInOnePieceAndInManyWithoutBeingCopiedTwice() {
        val big = "${esc}_Ga=t,f=24,s=1000,v=1000;" + "A".repeat(3_000_000) + "$esc\\"
        val bytes = big.toByteArray(Charsets.ISO_8859_1)
        val translator = OutputTranslator()

        val length = translator.filter(bytes, bytes.size)

        assertEquals(bytes.size, length)
        assertContentEquals(bytes, translator.output.copyOf(length))
    }

    // ---- Images

    private class Transmission(val control: String, val chunks: List<String>, val rgba: ByteArray)

    // The images of the Kitty protocol that are in [text]: the keys of the first chunk, the chunks, and the pixels.
    private fun transmissions(text: String): List<Transmission> {
        val chunks = Regex("\u001b_G([^;\u001b]*);([^\u001b]*)\u001b\\\\").findAll(text).toList()
        val result = ArrayList<Transmission>()
        var control = ""
        val payloads = ArrayList<String>()
        for (chunk in chunks) {
            if (chunk.groupValues[1].startsWith("a=")) control = chunk.groupValues[1]
            payloads += chunk.groupValues[2]
            if (chunk.groupValues[1].endsWith("m=0")) {
                result += Transmission(control, payloads.toList(), Base64.getDecoder().decode(payloads.joinToString("")))
                payloads.clear()
            }
        }
        return result
    }

    private fun sixel(body: String) = "${esc}P0;1;0q$body$esc\\"

    private fun png(width: Int = 4, height: Int = 2): String {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0xFFFF0000.toInt())
        return Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
    }

    @Test
    fun aSixelImageBecomesAnImageOfTheKittyProtocolAndTheCursorGoesToTheNextLine() {
        val stream = "before " + sixel("#1;2;100;0;0!4~") + " after"

        val result = filtered(stream)

        val image = transmissions(result).single()
        assertEquals("a=T,f=32,s=4,v=6,q=2,m=0", image.control)
        assertEquals(96, image.rgba.size)
        assertEquals(listOf(255, 0, 0, 255), image.rgba.take(4).map { it.toInt() and 0xFF })
        assertTrue(result.startsWith("before ${esc}_G"), "The text before comes first")
        assertTrue(result.endsWith("$esc\\\r\n after"), "Then the cursor goes down, and the text after follows")
        assertEquals(1, Regex("\u001bP").findAll(result).count() + 1, "The sixel itself is not passed on")
    }

    @Test
    fun aBigImageIsSentInChunksOfAtMost4096Characters() {
        val stream = sixel("#1;2;0;0;100" + "!100~-".repeat(10))

        val image = transmissions(filtered(stream)).single()

        assertEquals(100 * 60 * 4, image.rgba.size)
        assertTrue(image.chunks.size > 4, "${image.chunks.size} chunks")
        assertTrue(image.chunks.all { it.length <= 4096 })
        val text = filtered(stream)
        assertEquals(image.chunks.size - 1, Regex("m=1").findAll(text).count(), "All but the last say that there is more")
    }

    @Test
    fun anItermImageBecomesAnImageOfTheKittyProtocolWhicheverTerminatorItHas() {
        for (terminator in listOf(bel, "$esc\\")) {
            val result = filtered("a$esc]1337;File=inline=1;width=10:${png()}${terminator}b")

            val image = transmissions(result).single()
            assertEquals("a=T,f=32,s=4,v=2,c=10,q=2,m=0", image.control)
            assertEquals(4 * 2 * 4, image.rgba.size)
            assertTrue(result.startsWith("a${esc}_G") && result.endsWith("${esc}\\b"), "The text around it stays")
        }
    }

    @Test
    fun theCursorCanStayWhereItIsForAnItermImage() {
        val result = filtered("$esc]1337;File=inline=1;doNotMoveCursor=1:${png()}$bel")

        assertEquals("a=T,f=32,s=4,v=2,C=1,q=2,m=0", transmissions(result).single().control)
    }

    @Test
    fun theSizeInPercentIsOfTheTerminalThatTheSessionHas() {
        val translator = OutputTranslator(geometry = { CellGeometry(10, 20, 100, 50) })
        val bytes = "$esc]1337;File=inline=1;width=50%:${png()}$bel".toByteArray(Charsets.ISO_8859_1)

        val length = translator.filter(bytes, bytes.size)

        assertTrue(",c=50," in String(translator.output, 0, length, Charsets.ISO_8859_1))
    }

    @Test
    fun anImageThatIsNotShownOrCannotBeReadIsDropped() {
        assertEquals("x", filtered("${esc}Pq$esc\\x"), "A sixel with nothing")
        assertEquals("x", filtered("${esc}P0;1;0q\n\n$esc\\x"), "A sixel that draws nothing")
        assertEquals("x", filtered("$esc]1337;File=inline=0:${png()}${bel}x"), "A download")
        assertEquals("x", filtered("$esc]1337;File=inline=1:bm90IGFuIGltYWdl${bel}x"), "Not an image")
    }

    @Test
    fun aSixelImageAndAnItermImageAreTheSameWhicheverWayTheStreamIsCut() {
        assertTheSameWhenCut("a" + sixel("#1;2;100;0;0!4~-!4~") + "b$esc[?12lc")
        assertTheSameWhenCut("a$esc]1337;File=inline=1;width=3:${png()}${bel}b$esc[?12lc")
        assertTheSameWhenCut("a$esc]1337;File=inline=1:${png()}$esc\\b")
    }

    @Test
    fun anImageThatIsTooBigIsDroppedAndWhatFollowsIsRead() {
        val translator = OutputTranslator(maxPayload = 100)
        val out = StringBuilder()
        for (piece in listOf("a${esc}P0;1;0q#1;2;100;0;0", "!10~".repeat(40), "$esc\\b$esc[?12lc")) {
            val bytes = piece.toByteArray(Charsets.ISO_8859_1)
            val length = translator.filter(bytes, bytes.size)
            out.append(String(translator.output, 0, length, Charsets.ISO_8859_1))
        }

        assertEquals("abc", out.toString())
    }

    @Test
    fun aStringThatIsCancelledWhileItIsKeptIsGivenUp() {
        assertEquals("\u0018x", filtered("${esc}P0;1;0q#1~\u0018x"))
    }

    @Test
    fun theOutputBufferThatAnImageNeededIsNotKept() {
        val translator = OutputTranslator()
        val big = sixel("#1;2;100;0;0" + "!2000~-".repeat(600)).toByteArray(Charsets.ISO_8859_1)
        translator.filter(big, big.size)
        val small = "${esc}[1mtext${esc}[0m".toByteArray()

        val length = translator.filter(small, small.size)

        assertEquals(small.size, length)
        assertSame(small, translator.output, "A piece that is not changed is given back as it is")
    }
}
