package app.katty.terminal

import app.katty.ghostty.GhosttyScreenPoint
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandMarksTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach { it.close() }

    private fun TerminalFrame.text(row: Int): String = rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}\n${frames.value.commandMarks}", e)
        }
    }

    // A program that writes what a shell with the integration does: the prompt (A), the end of it (B), the start of the output (C) and its end (D).
    private fun shell(script: String, rows: Int = 8): TerminalSession =
        TerminalSession.start(40, rows, command = listOf("/bin/sh", "-c", "$script; sleep 30")).also { sessions += it }

    private val a = """\033]133;A\007"""
    private val b = """\033]133;B\007"""
    private val c = """\033]133;C\007"""
    private fun d(code: Int? = null) = """\033]133;D${code?.let { ";$it" } ?: ""}\007"""

    // A command as the shell draws it: the prompt, what was typed, the output.
    private fun command(typed: String, output: String, code: Int? = 0, prompt: String = "$ ") =
        "$a$prompt$b$typed\\r\\n$c$output${d(code)}"

    private fun printf(vararg parts: String) = "printf '" + parts.joinToString("") + "'"

    private fun TerminalSession.settled(count: Int) =
        awaitFrame { it.commandMarks.size == count && it.commandMarks.all { mark -> mark.finishedAt != null } }.commandMarks

    @Test
    fun aMarkCoversThePromptAndTheOutputOfTheCommand() {
        // Row 0: "$ ls", row 1: "one", row 2: the next prompt, which no command followed.
        val session = shell(printf(command("ls", "one\\r\\n"), "$a$ $b"))

        val mark = session.settled(1).single()

        assertEquals(0, mark.top)
        assertEquals(1, mark.bottom, "Not the row of the next prompt")
        assertEquals(0, mark.exitCode)
        assertEquals(TerminalCommandOutcome.SUCCEEDED, mark.outcome)
        assertTrue(!mark.startsAbove && !mark.endsBelow)
    }

    @Test
    fun aMarkStartsOnThePromptAndNotOnTheEmptyLineBeforeIt() {
        // A theme's prompt starts with an empty line, after the mark that starts the prompt.
        val session = shell(printf(command("ls", "one\\r\\n", prompt = "\\r\\n$ "), "$a\\r\\n$ $b"))

        val mark = session.settled(1).single()

        // Row 0 is empty, row 1 is "$ ls", row 2 is "one".
        assertEquals(1, mark.top)
        assertEquals(2, mark.bottom)
    }

    @Test
    fun aCommandWithNoOutputIsItsPromptRows() {
        val session = shell(printf(command("false", "", code = 1), "$a$ $b"))

        val mark = session.settled(1).single()

        assertEquals(0, mark.top)
        assertEquals(0, mark.bottom)
        assertEquals(TerminalCommandOutcome.FAILED, mark.outcome)
    }

    @Test
    fun aCommandThatRunsGoesDownToTheCursor() {
        val session = shell(printf("$a$ $b", "sleep\\r\\n$c", "out"))

        val mark = session.awaitFrame { it.commandMarks.isNotEmpty() && "out" in it.text() }.commandMarks.single()

        assertEquals(TerminalCommandOutcome.RUNNING, mark.outcome)
        assertEquals(0, mark.top)
        assertEquals(1, mark.bottom, "The cursor is on the row of the output")
    }

    @Test
    fun twoCommandsThatFollowEachOtherAreTwoMarksThatDoNotOverlap() {
        val session = shell(printf(command("ls", "one\\r\\n"), command("false", "", code = 1), "$a$ $b"))

        val marks = session.settled(2)

        assertEquals(listOf(0 to 1, 2 to 2), marks.map { it.top to it.bottom })
        assertEquals(listOf(TerminalCommandOutcome.SUCCEEDED, TerminalCommandOutcome.FAILED), marks.map { it.outcome })
        assertTrue(marks.all { !it.finishedAt!!.isBefore(it.startedAt) })
    }

    @Test
    fun aCommandThatEndsWithNoCodeHasEnded() {
        val session = shell(printf(command("x", "", code = null), "$a$ $b"))

        assertEquals(TerminalCommandOutcome.ENDED, session.settled(1).single().outcome)
    }

    @Test
    fun aPromptThatNoCommandFollowsHasNoMark() {
        // The user pressed Return on an empty line, twice.
        val session = shell(printf("$a$ $b\\r\\n$a$ $b\\r\\n$a$ ready"))
        session.awaitFrame { "ready" in it.text() }

        assertEquals(emptyList(), session.frames.value.commandMarks)
    }

    @Test
    fun aMarkThatStartedAboveTheViewportIsCutAtItsTop() {
        // The command runs: its output scrolls its prompt into the history.
        val session = shell(printf("$a$ $b", "seq\\r\\n$c") + "; seq 1 30", rows = 5)

        val frame = session.awaitFrame { it.scrollbar != null && it.commandMarks.isNotEmpty() && "30" in it.text() }
        val mark = frame.commandMarks.single()

        assertTrue(mark.startsAbove)
        assertEquals(0, mark.top)
        assertFalse(mark.endsBelow)
        assertEquals(frame.cursor.y, mark.bottom.let { it + 1 }.takeIf { frame.cursor.x == 0 } ?: mark.bottom)
    }

    @Test
    fun aMarkThatGoesOnBelowTheViewportIsCutAtItsBottom() {
        val session = shell(printf("$a$ $b", "seq\\r\\n$c") + "; seq 1 30", rows = 5)
        session.awaitFrame { it.scrollbar != null && it.commandMarks.isNotEmpty() && "30" in it.text() }

        session.scrollToTop()

        // The prompt is at the top of the history, and the command goes on below the viewport.
        val mark = session.awaitFrame { it.scrollbar?.offset == 0L && it.commandMarks.isNotEmpty() }.commandMarks.single()
        assertEquals(0, mark.top)
        assertEquals(4, mark.bottom)
        assertTrue(mark.endsBelow)
        assertFalse(mark.startsAbove)
    }

    @Test
    fun theMarksOfWhatIsAboveTheViewportAreNotInTheFrame() {
        val session = shell(printf(command("ls", "one\\r\\n")) + "; seq 1 30; printf end", rows = 5)

        val frame = session.awaitFrame { "end" in it.text() }

        assertTrue(frame.scrollbar != null, "The command is in the history")
        assertEquals(emptyList(), frame.commandMarks)

        // And it comes back with the history.
        session.scrollToTop()
        assertEquals(0 to 1, session.awaitFrame { it.commandMarks.isNotEmpty() }.commandMarks.single().let { it.top to it.bottom })
    }

    // What a shell does when the screen is cleared at its prompt (Ctrl-L in zsh): it draws the prompt again at the top, and sends the end of the
    // prompt (it is in PS1) but not the start of it.
    private val ctrlL = """\033[H\033[2J"""

    // What ⌘L does: the history goes too.
    private val commandL = """\033[H\033[2J\033[3J"""

    @Test
    fun aPromptThatIsDrawnAgainAfterCtrlLIsWhereTheMarkStarts() {
        val session = shell(printf("$a$ $b$ctrlL", "$ $b", "ls\\r\\n$c", "one\\r\\n${d(0)}", "$a$ $b"))

        val mark = session.settled(1).single()

        // The prompt that was above is in the history: the command starts on the prompt that was drawn again.
        assertEquals(0, mark.top)
        assertEquals(1, mark.bottom)
    }

    @Test
    fun aPromptThatIsDrawnAgainAfterCommandLIsWhereTheMarkStarts() {
        val session = shell(printf("$a$ $b$commandL", "$ $b", "ls\\r\\n$c", "one\\r\\n${d(0)}", "$a$ $b"))

        val mark = session.settled(1).single()

        assertEquals(0, mark.top, "Not the first row of the output")
        assertEquals(1, mark.bottom)
    }

    @Test
    fun aPromptOfSeveralRowsStartsOnItsFirstRowAlsoWhenItIsDrawnAgain() {
        val prompt = "info\\r\\n$ "
        val session = shell(printf("$a$prompt$b$ctrlL", "$prompt$b", "ls\\r\\n$c", "one\\r\\n${d(0)}", "$a$ $b"))

        val mark = session.settled(1).single()

        // Rows: "info", "$ ls", "one".
        assertEquals(0, mark.top)
        assertEquals(2, mark.bottom)
    }

    @Test
    fun clearingTheScreenForgetsTheCommandsThatWereOnIt() {
        // The shell's prompt waits for a command (B was sent): ⌘L erases the screen and the history.
        val session = shell(printf(command("ls", "one\\r\\n"), "$a$ $b"))
        session.awaitFrame { it.commandMarks.size == 1 }

        session.clearScreen()

        // (The pty echoes the ^L that is sent for the shell: the screen is cleared when the command is not on it any more.)
        session.awaitFrame { "ls" !in it.text() && it.commandMarks.isEmpty() }
    }

    @Test
    fun theAlternateScreenHasNoMarks() {
        val session = shell(printf(command("ls", "one\\r\\n"), "\\033[?1049hvim"))

        session.awaitFrame { it.alternateScreen && "vim" in it.text() }

        assertEquals(emptyList(), session.frames.value.commandMarks)
    }

    @Test
    fun selectsTheOutputOfTheCommandOfAMarkAndNotOfTheLastOne() {
        val session = shell(printf(command("ls", "one\\r\\n"), command("ls", "two\\r\\n"), "$a$ $b"))
        val marks = session.settled(2)

        assertTrue(session.selectCommandOutput(marks[0].id))

        assertEquals("one", session.selectedText())
        assertTrue(session.selectCommandOutput(marks[1].id))
        assertEquals("two", session.selectedText())
    }

    @Test
    fun selectingTheOutputOfACommandThatIsForgottenSelectsNothing() {
        val session = shell(printf(command("ls", "one\\r\\n"), "$a$ ready"))
        val mark = assertNotNull(session.awaitFrame { it.commandMarks.isNotEmpty() }.commandMarks.firstOrNull())

        assertEquals(false, session.selectCommandOutput(mark.id + 100))
        assertEquals(null, session.selectedText())
    }

    // The rows of the viewport are text, and the commands are where the screen says: no terminal.
    private fun rows(vararg texts: String) = texts.mapIndexed { i, text ->
        TerminalRow(TerminalRowId(i.toLong(), 0), text.map { TerminalCell(it.toString(), null, null, TerminalStyle.NONE, TerminalCellWidth.NARROW) })
    }

    private fun span(start: Long, end: GhosttyScreenPoint?, finished: Boolean = end != null) =
        CommandSpan(1, start, end, 0, Instant.EPOCH, if (finished) Instant.EPOCH else null)

    @Test
    fun theBlankRowsAtTheStartOfACommandAreNotItsOwn() {
        val marks = commandMarks(listOf(span(2, GhosttyScreenPoint(0, 6))), offset = 0, rows("", "", " ", "$ ls", "one", "two", ""), cursor = null)

        assertEquals(3 to 5, marks.single().let { it.top to it.bottom })
    }

    @Test
    fun aCommandThatStartedAboveTheViewportDoesNotSkipItsFirstRows() {
        // Its start is above: the rows at the top of the viewport are its output, blank or not.
        val marks = commandMarks(listOf(span(2, GhosttyScreenPoint(5, 14))), offset = 10, rows("", "x", "y"), cursor = null)

        val mark = marks.single()
        assertEquals(0, mark.top)
        assertEquals(2, mark.bottom)
        assertTrue(mark.startsAbove)
        assertTrue(mark.endsBelow)
    }

    @Test
    fun aCommandThatIsEntirelyAboveOrBelowTheViewportHasNoMark() {
        val above = span(0, GhosttyScreenPoint(3, 4))
        val below = span(20, GhosttyScreenPoint(3, 22))

        assertEquals(emptyList(), commandMarks(listOf(above, below), offset = 10, rows("a", "b", "c"), cursor = null))
    }

    @Test
    fun aCommandThatRunsGoesDownToTheCursorOrToTheBottomWhenTheCursorIsNotInView() {
        val running = span(0, null)

        assertEquals(1, commandMarks(listOf(running), 0, rows("$ x", "out", ""), cursor = 3 to 1).single().bottom)
        assertEquals(1, commandMarks(listOf(running), 0, rows("$ x", "out", ""), cursor = 0 to 2).single().bottom, "The cursor is at the start of an empty row")
        val below = commandMarks(listOf(running), 0, rows("$ x", "out", "more"), cursor = null).single()
        assertEquals(2, below.bottom)
        assertTrue(below.endsBelow)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
