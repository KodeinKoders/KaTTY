package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttyTerminalEffectsTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5)
    private val replies = StringBuilder()
    private val log = mutableListOf<String>()

    init {
        terminal.onWritePty = { replies.append(it.toString(Charsets.UTF_8)) }
    }

    @AfterTest
    fun close() {
        terminal.close()
    }

    private val bel = "\u0007"
    private val esc = "\u001b"

    @Test
    fun reportsTheBell() {
        terminal.onBell = { log += "bell" }

        terminal.vtWrite("a${bel}b")

        assertEquals(listOf("bell"), log)
    }

    @Test
    fun reportsTheTitleAndKeepsIt() {
        terminal.onTitleChanged = { log += "title" }
        assertEquals("", terminal.title)

        terminal.vtWrite("$esc]2;my title$bel")

        assertEquals(listOf("title"), log)
        assertEquals("my title", terminal.title)
    }

    @Test
    fun reportsTheWorkingDirectoryAsTheShellSentIt() {
        terminal.onPwdChanged = { log += "pwd" }
        assertEquals("", terminal.pwd)

        terminal.vtWrite("$esc]7;file://host/a%20b$bel")

        assertEquals(listOf("pwd"), log)
        assertEquals("file://host/a%20b", terminal.pwd)
    }

    @Test
    fun reportsNotifications() {
        terminal.onNotification = { title, body -> log += "$title|$body" }

        terminal.vtWrite("$esc]9;just a body$bel")
        terminal.vtWrite("$esc]777;notify;The title;The body$bel")

        assertEquals(listOf("|just a body", "The title|The body"), log)
    }

    @Test
    fun reportsProgress() {
        val reports = mutableListOf<GhosttyProgress>()
        terminal.onProgress = { reports += it }

        terminal.vtWrite("$esc]9;4;1;50$bel")
        terminal.vtWrite("$esc]9;4;3$bel")
        terminal.vtWrite("$esc]9;4;0$bel")

        assertEquals(
            listOf(
                GhosttyProgress(GhosttyProgressState.SET, 50),
                GhosttyProgress(GhosttyProgressState.INDETERMINATE, null),
                GhosttyProgress(GhosttyProgressState.REMOVE, null),
            ),
            reports,
        )
    }

    @Test
    fun reportsTheStepsOfThePrompt() {
        val steps = mutableListOf<GhosttySemanticPrompt>()
        terminal.onSemanticPrompt = { steps += it }

        terminal.vtWrite("$esc]133;A$bel$ $esc]133;B$bel")
        terminal.vtWrite("$esc]133;C$bel")
        terminal.vtWrite("$esc]133;D;3$bel")

        assertEquals(
            listOf(
                GhosttyPromptEventKind.PROMPT_START,
                GhosttyPromptEventKind.INPUT_START,
                GhosttyPromptEventKind.OUTPUT_START,
                GhosttyPromptEventKind.COMMAND_END,
            ),
            steps.map { it.kind },
        )
        assertEquals(3, steps.last().exitCode)
        assertNull(steps.first().exitCode)
    }

    @Test
    fun saysWhichPromptAPromptStartIs() {
        val steps = mutableListOf<GhosttySemanticPrompt>()
        terminal.onSemanticPrompt = { steps += it }

        terminal.vtWrite("$esc]133;A$bel$esc]133;A;k=s$bel$esc]133;A;k=c$bel$esc]133;A;k=r$bel")

        assertEquals(
            listOf(GhosttyPromptKind.PRIMARY, GhosttyPromptKind.SECONDARY, GhosttyPromptKind.CONTINUATION, GhosttyPromptKind.RIGHT),
            steps.filter { it.kind == GhosttyPromptEventKind.PROMPT_START }.map { it.promptKind },
        )
    }

    @Test
    fun reportsAClipboardWriteWithItsDecodedText() {
        terminal.onClipboardWrite = { location, text -> log += "$location|$text"; true }

        terminal.vtWrite("$esc]52;c;aGVsbG8=$bel")
        terminal.vtWrite("$esc]52;p;d29ybGQ=$esc\\")

        assertEquals(listOf("STANDARD|hello", "PRIMARY|world"), log)
    }

    @Test
    fun aClipboardWriteThatIsNotAllowedIsRefused() {
        var asked = false
        terminal.onClipboardWrite = { _, _ -> asked = true; false }

        terminal.vtWrite("$esc]52;c;aGVsbG8=$bel")

        assertTrue(asked)
        assertEquals("", replies.toString())
    }

    @Test
    fun withoutAHandlerAClipboardWriteIsIgnored() {
        terminal.vtWrite("$esc]52;c;aGVsbG8=$bel")

        assertEquals("", replies.toString())
    }

    @Test
    fun answersAClipboardReadWithTheTextOfTheReader() {
        terminal.setClipboardReader { location ->
            log += "read $location"
            "hi"
        }

        terminal.vtWrite("$esc]52;c;?$bel")

        assertEquals(listOf("read STANDARD"), log)
        // "hi" in base64.
        assertTrue("aGk=" in replies, "The answer was: ${replies.toString().replace(esc, "ESC")}")
    }

    @Test
    fun doesNotAnswerAClipboardReadWithoutAReader() {
        terminal.vtWrite("$esc]52;c;?$bel")
        assertFalse("aGk=" in replies)

        terminal.setClipboardReader { "secret" }
        terminal.setClipboardReader(null)
        terminal.vtWrite("$esc]52;c;?$bel")
        assertFalse("c2VjcmV0" in replies, "A read is answered after the reader was cleared")
    }

    @Test
    fun aReaderThatRefusesGetsNoAnswerSent() {
        terminal.setClipboardReader { null }

        terminal.vtWrite("$esc]52;c;?$bel")

        assertFalse("aGk=" in replies)
    }

    @Test
    fun reportsAResetAndNotWhatItCleared() {
        terminal.onReset = { log += "reset" }
        terminal.onTitleChanged = { log += "title" }
        terminal.vtWrite("$esc]2;t$bel")
        log.clear()

        terminal.vtWrite("${esc}c")

        assertEquals(listOf("reset"), log)
        assertEquals("", terminal.title)
    }

    @Test
    fun scrollsToThePreviousPrompt() {
        repeat(20) { terminal.vtWrite("$esc]133;A$bel$ command $it\r\noutput of $it\r\n$esc]133;C$bel") }
        val bottom = terminal.scrollbar.offset

        terminal.scroll(GhosttyScrollViewport.DeltaPrompt(-1))
        val first = terminal.scrollbar.offset
        terminal.scroll(GhosttyScrollViewport.DeltaPrompt(-1))
        val second = terminal.scrollbar.offset

        assertTrue(first < bottom, "The viewport moved up from $bottom to $first")
        assertTrue(second < first, "and again, to $second")

        terminal.scroll(GhosttyScrollViewport.DeltaPrompt(1))
        assertEquals(first, terminal.scrollbar.offset)
    }

    @Test
    fun scrollingToAPromptDoesNothingWithoutMarks() {
        repeat(20) { terminal.vtWrite("line $it\r\n") }
        val before = terminal.scrollbar.offset

        terminal.scroll(GhosttyScrollViewport.DeltaPrompt(-1))

        assertEquals(before, terminal.scrollbar.offset)
    }

    @Test
    fun givesTheAddressOfAHyperlink() {
        terminal.vtWrite("$esc]8;;https://example.com/a?b=1$esc\\link$esc]8;;$esc\\ plain")

        assertEquals("https://example.com/a?b=1", terminal.hyperlinkAt(0, 0))
        assertEquals("https://example.com/a?b=1", terminal.hyperlinkAt(3, 0))
        assertNull(terminal.hyperlinkAt(4, 0))
        assertNull(terminal.hyperlinkAt(7, 0))
        assertNull(terminal.hyperlinkAt(30, 0), "A cell that is not on the screen has none")
        assertNull(terminal.hyperlinkAt(0, 4))
    }

    @Test
    fun marksTheCellsOfAHyperlinkWhenRendering() {
        terminal.vtWrite("$esc]8;;https://example.com$esc\\link$esc]8;;$esc\\ plain")
        GhosttyRenderState().use { state ->
            state.update(terminal)
            val cells = state.readRows()[0].cells!!

            assertEquals(listOf(true, true, true, true, false, false), cells.take(6).map { it.hyperlink })
        }
    }
}
