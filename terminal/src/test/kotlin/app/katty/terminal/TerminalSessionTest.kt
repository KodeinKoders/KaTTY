package app.katty.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TerminalSessionTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
    }

    // A shell script instead of the login shell, so that what the program does is known.
    private fun start(script: String, cols: Int = 80, rows: Int = 5): TerminalSession =
        TerminalSession.start(cols, rows, command = listOf("/bin/sh", "-c", script)).also { sessions += it }

    private fun TerminalFrame.text(row: Int): String =
        rows[row].cells.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    private fun TerminalFrame.text(): String = rows.indices.joinToString("\n") { text(it) }

    private fun TerminalSession.awaitFrame(condition: (TerminalFrame) -> Boolean): TerminalFrame = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { frames.first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("No frame matched in ${TIMEOUT_MS}ms. The last one:\n${frames.value.text()}", e)
        }
    }

    private fun TerminalSession.awaitExit(): Int = runBlocking {
        withTimeout(TIMEOUT_MS) { exited.filterNotNull().first() }
    }

    @Test
    fun publishesWhatTheProgramWrites() {
        val session = start("printf hello")

        val frame = session.awaitFrame { it.text(0) == "hello" }

        assertEquals(80, frame.cols)
        assertEquals(5, frame.rows.size)
        assertEquals(0, session.awaitExit())
    }

    @Test
    fun runsTheProgramInATerminalThatSupportsColors() {
        val session = start("""printf 'a\033[1;31mb\033[0m'; echo; echo "${'$'}TERM ${'$'}COLORTERM"; tput colors""")

        val frame = session.awaitFrame { it.text(2) == "256" }

        assertEquals("xterm-256color truecolor", frame.text(1).trimEnd())
        val (plain, styled) = frame.rows[0].cells
        assertEquals(TerminalStyle.NONE, plain.style)
        assertTrue(styled.style.bold)
        assertNotNull(styled.foreground)
    }

    @Test
    fun reflectsAResize() {
        val session = start("sleep 30")

        session.resize(cols = 40, rows = 10, cellWidthPx = 8, cellHeightPx = 16)

        val frame = session.awaitFrame { it.cols == 40 && it.rows.size == 10 }
        assertEquals(10, frame.rows.size)
    }

    @Test
    fun tellsTheProgramTheSizeOfItsTerminal() {
        val session = start("""sleep 0.5; stty size""")

        // The size is changed while the program sleeps: it is the new one that it reads.
        session.resize(cols = 33, rows = 7, cellWidthPx = 8, cellHeightPx = 16)

        session.awaitFrame { frame -> frame.rows.indices.any { frame.text(it) == "7 33" } }
    }

    @Test
    fun sendsEnterAsACarriageReturn() {
        // In raw mode, the program gets exactly the bytes that are sent. "ready" means it is in raw mode.
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.ENTER))

        session.awaitFrame { "0d" in it.text() }
    }

    @Test
    fun encodesKeysAccordingToTheModesTheProgramSet() {
        // The program asks for the Kitty keyboard protocol (CSI > 1 u) before it reads.
        val session = start("""printf '\033[>1u'; stty raw -echo; echo ready; head -c7 | od -An -tx1""")
        session.awaitFrame { "ready" in it.text() }

        session.sendKey(KeyInput(TerminalKey.ENTER, mods = setOf(TerminalModifier.SHIFT)))

        // ESC [ 1 3 ; 2 u, which od separates with more than a space.
        session.awaitFrame { "1b 5b 31 33 3b 32 75" in it.text().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun keepsTheRowsThatDidNotChange() {
        val session = start("printf one; sleep 0.5; printf '\\r\\ntwo'; sleep 30")

        val first = session.awaitFrame { it.text(0) == "one" && it.text(1).isEmpty() }
        val second = session.awaitFrame { it.text(1) == "two" }

        assertSame(first.rows[0], second.rows[0], "A row that did not change is the same object")
        assertEquals(first.rows[0].id, second.rows[0].id)
    }

    @Test
    fun limitsTheFrameRateWhenTheOutputFloods() {
        val started = TimeSource.Monotonic.markNow()
        val session = start("seq 1 300000")

        var published = 0
        val last = runBlocking {
            withTimeout(TIMEOUT_MS) { session.frames.onEach { published++ }.first { "300000" in it.text() } }
        }
        val elapsed = started.elapsedNow()

        // The last output is published, not merged away by the frames that came before it.
        assertTrue("300000" in last.text())
        // An upper bound that does not depend on the speed of the machine: a frame is produced at most
        // every 1/60 s, and the one of the start of the session comes on top.
        val bound = (elapsed / (1.seconds / 60)).toInt() + 2
        assertTrue(published <= bound, "$published frames in $elapsed, at most $bound are allowed")
    }

    private val theme = TerminalTheme(
        background = TerminalColor(0x102030),
        foreground = TerminalColor(0xE0D0C0),
        ansi = List(16) { TerminalColor(0x203040 + it * 0x050709) },
        cursor = TerminalColor(0xFF8800),
        cursorText = TerminalColor(0x001122),
        bold = TerminalColor(0xFFFFFF),
    )

    @Test
    fun startsWithTheColorsOfTheTheme() {
        val session = TerminalSession.start(
            80, 5, command = listOf("/bin/sh", "-c", """printf 'a\033[31mb\033[44mc'; sleep 30"""), theme = theme,
        ).also { sessions += it }

        val frame = session.awaitFrame { it.text(0) == "abc" }

        assertEquals(theme.background, frame.background)
        assertEquals(theme.foreground, frame.foreground)
        assertEquals(theme.cursor, frame.cursorColor)
        assertEquals(theme.cursorText, frame.cursorTextColor)
        assertEquals(theme.bold, frame.boldColor)
        val cells = frame.rows[0].cells
        assertEquals(theme.ansi[1], cells[1].foreground, "SGR 31 is the theme's red")
        assertEquals(theme.ansi[4], cells[2].background, "SGR 44 is the theme's blue")
    }

    @Test
    fun changesTheColorsOfWhatIsOnTheScreen() {
        val session = start("""printf '\033[31mx\033[0m'; sleep 30""")
        val before = session.awaitFrame { it.text(0) == "x" }
        assertNotEquals(theme.ansi[1], before.rows[0].cells[0].foreground)

        session.setTheme(theme)

        val after = session.awaitFrame { it.rows[0].cells[0].foreground == theme.ansi[1] }
        assertEquals(theme.background, after.background)
        assertEquals(theme.cursorText, after.cursorTextColor)
    }

    @Test
    fun goesBackToTheDefaultColorsWithoutATheme() {
        val session = start("""printf '\033[31mx\033[0m'; sleep 30""")
        val before = session.awaitFrame { it.text(0) == "x" }
        session.setTheme(theme)
        session.awaitFrame { it.background == theme.background }

        session.setTheme(null)

        val after = session.awaitFrame { it.background == before.background }
        assertEquals(before.rows[0].cells[0].foreground, after.rows[0].cells[0].foreground)
        assertEquals(null, after.cursorTextColor)
        assertEquals(null, after.boldColor)
    }

    @Test
    fun aProgramsColorsWinOverTheTheme() {
        val session = start("""printf '\033]11;rgb:ff/00/00\033\\'; sleep 30""")
        session.setTheme(theme)

        // Whichever of the two comes first, the program's background is the one that is used, and the theme's
        // foreground, which the program did not change.
        session.awaitFrame { it.background == TerminalColor(0xFF0000) && it.foreground == theme.foreground }
    }

    // Every frame that is published, which a collector that is slow would not see: it runs where the producer does.
    private fun TerminalSession.recordFrames(): Pair<List<TerminalFrame>, Job> {
        val seen = CopyOnWriteArrayList<TerminalFrame>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { frames.collect { seen += it } }
        return seen to job
    }

    @Test
    fun showsARedrawThatTakesSeveralWritesWhole() {
        // What zsh does when the selection moves in its completion menu: the cursor goes down to the menu,
        // the entries are drawn, and the cursor goes back up to the line, in writes that are about half a
        // millisecond apart. A frame of the middle of it would show the cursor in the menu. It is done many
        // times, with pauses: whether the middle is caught is a matter of timing.
        val script = """perl -e '${'$'}| = 1; print "prompt> ab"; for (1..40) { select(undef, undef, undef, 0.025); """ +
            """print "\r\n"; select(undef, undef, undef, 0.001); print "\e[A\e[11C"; } print "done"; select(undef, undef, undef, 30)'"""
        val session = start(script)
        val (seen, job) = session.recordFrames()

        try {
            session.awaitFrame { "done" in it.text(0) }
        } finally {
            job.cancel()
        }

        assertTrue(seen.any { it.text(0) == "prompt> ab" }, "The prompt was shown")
        // Without waiting for the output to pause, about a quarter of the redraws are caught in the middle. The
        // two that are allowed are for the program being preempted for longer than the wait, on a busy machine.
        val inTheMiddle = seen.count { it.cursor.y == 1 }
        assertTrue(inTheMiddle <= 2, "The cursor was shown in the middle of the redraw in $inTheMiddle of the 40 redraws")
    }

    @Test
    fun stillPublishesWhileTheProgramKeepsWriting() {
        // Waiting for the output to pause must not starve the screen of a program that never does.
        val session = start("""perl -e '${'$'}| = 1; for (1..300) { print "x"; select(undef, undef, undef, 0.001) } select(undef, undef, undef, 30)'""")
        val (seen, job) = session.recordFrames()

        try {
            session.awaitFrame { frame -> frame.text().count { it == 'x' } == 300 }
        } finally {
            job.cancel()
        }

        assertTrue(seen.size >= 8, "Only ${seen.size} frames were published while the program wrote for 300ms")
    }

    @Test
    fun theCursorBlinksUnlessTheProgramOrTheCallerSaysOtherwise() {
        val session = start("sleep 30")
        assertTrue(session.awaitFrame { true }.cursor.blinking, "A terminal's cursor blinks")

        session.writeToScreen("\u001b[2 q")
        assertEquals(false, session.awaitFrame { !it.cursor.blinking }.cursor.blinking, "A steady cursor is what the program asked for")

        val steady = TerminalSession.start(80, 5, command = listOf("/bin/sh", "-c", "sleep 30"), cursorBlink = false).also { sessions += it }
        assertEquals(false, steady.awaitFrame { true }.cursor.blinking)
    }

    @Test
    fun writesAMessageOnTheScreenAfterWhatTheProgramWrote() {
        val session = start("printf hello")
        val code = session.awaitExit()

        session.writeToScreen("\r\n[exited with code $code]")

        val frame = session.awaitFrame { it.text(1) == "[exited with code 0]" }
        assertEquals("hello", frame.text(0), "The message comes after what the program wrote")
    }

    @Test
    fun doesNotSendWhatItWritesOnTheScreenToTheProgram() {
        // In raw mode, the program gets exactly the bytes that are sent: it must not get the message.
        val session = start("stty raw -echo; echo ready; head -c1 | od -An -tx1")
        session.awaitFrame { "ready" in it.text() }

        session.writeToScreen("message")
        session.sendKey(KeyInput(TerminalKey.ENTER))

        // The program only got the Enter, which is the one byte it reads.
        val frame = session.awaitFrame { "0d" in it.text() }
        assertTrue("message" in frame.text())
    }

    @Test
    fun ignoresWhatIsWrittenOnTheScreenOnceClosed() {
        val session = start("sleep 30")
        session.close()

        session.writeToScreen("too late")
    }

    @Test
    fun endsTheProgramWhenClosed() {
        val session = start("sleep 30")
        session.awaitFrame { true }

        session.close()

        // 0 would be the exit code of a program that ran to its end.
        assertNotEquals(0, session.awaitExit())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
